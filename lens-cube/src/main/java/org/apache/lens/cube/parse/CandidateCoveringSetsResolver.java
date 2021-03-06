/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.cube.parse;

import static org.apache.lens.cube.parse.CandidateUtil.getColumns;

import java.util.*;

import org.apache.lens.cube.error.LensCubeErrorCode;
import org.apache.lens.cube.metadata.TimeRange;
import org.apache.lens.server.api.error.LensException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CandidateCoveringSetsResolver implements ContextRewriter {

  @Override
  public void rewriteContext(CubeQueryContext cubeql) throws LensException {

    if (!cubeql.hasCubeInQuery()) {
      return; //Dimension query
    }

    if (cubeql.getCandidates().size() == 0){
      cubeql.throwNoCandidateFactException();
    }

    List<QueriedPhraseContext> qpcList = cubeql.getQueriedPhrases();
    Set<QueriedPhraseContext> queriedMsrs = new HashSet<>();
    for (QueriedPhraseContext qpc : qpcList) {
      if (qpc.hasMeasures(cubeql)) {
        queriedMsrs.add(qpc);
      }
    }

    List<Candidate> timeRangeCoveringSet = resolveTimeRangeCoveringFactSet(cubeql, queriedMsrs, qpcList);
    if (timeRangeCoveringSet.isEmpty()) {
      throw new LensException(LensCubeErrorCode.NO_UNION_CANDIDATE_AVAILABLE.getLensErrorInfo(),
          cubeql.getCube().getName(), cubeql.getTimeRanges().toString(), getColumns(queriedMsrs).toString());
    }
    log.info("Time covering candidates :{}", timeRangeCoveringSet);

    if (queriedMsrs.isEmpty()) {
      cubeql.getCandidates().clear();
      cubeql.getCandidates().addAll(timeRangeCoveringSet);
    } else if (!timeRangeCoveringSet.isEmpty()) {
      List<List<Candidate>> measureCoveringSets = resolveJoinCandidates(timeRangeCoveringSet, queriedMsrs, cubeql);
      if (measureCoveringSets.isEmpty()) {
        throw new LensException(LensCubeErrorCode.NO_JOIN_CANDIDATE_AVAILABLE.getLensErrorInfo(),
            cubeql.getCube().getName(), getColumns(queriedMsrs).toString());
      }
      updateFinalCandidates(measureCoveringSets, cubeql);
    }

    log.info("Final Time and Measure covering candidates :{}", cubeql.getCandidates());
  }

  private Candidate createJoinCandidate(List<Candidate> childCandidates, CubeQueryContext cubeql) {
    Candidate cand;
    Candidate first = childCandidates.get(0);
    Candidate second = childCandidates.get(1);
    cand = new JoinCandidate(first, second, cubeql);
    for (int i = 2; i < childCandidates.size(); i++) {
      cand = new JoinCandidate(cand, childCandidates.get(i), cubeql);
    }
    return cand;
  }

  private void updateFinalCandidates(List<List<Candidate>> joinCandidates, CubeQueryContext cubeql) {
    List<Candidate> finalCandidates = new ArrayList<>();

    for (List<Candidate> joinCandidate : joinCandidates) {
      if (joinCandidate.size() == 1) {
        finalCandidates.add(joinCandidate.iterator().next());
      } else {
        finalCandidates.add(createJoinCandidate(joinCandidate, cubeql));
      }
    }
    cubeql.getCandidates().clear();
    cubeql.getCandidates().addAll(finalCandidates);
  }

  private boolean isCandidateCoveringTimeRanges(UnionCandidate uc, List<TimeRange> ranges) {
    for (TimeRange range : ranges) {
      if (!CandidateUtil.isTimeRangeCovered(uc.getChildren(), range.getFromDate(), range.getToDate())) {
        return false;
      }
    }
    return true;
  }

  private void pruneUnionCandidatesNotCoveringAllRanges(List<UnionCandidate> ucs, CubeQueryContext cubeql) {
    for (Iterator<UnionCandidate> itr = ucs.iterator(); itr.hasNext();) {
      UnionCandidate uc = itr.next();
      if (!isCandidateCoveringTimeRanges(uc, cubeql.getTimeRanges())) {
        itr.remove();
        cubeql.addCandidatePruningMsg(uc, CandidateTablePruneCause.storageNotAvailableInRange(cubeql.getTimeRanges()));
      }
    }
  }

  private List<Candidate> resolveTimeRangeCoveringFactSet(CubeQueryContext cubeql,
      Set<QueriedPhraseContext> queriedMsrs, List<QueriedPhraseContext> qpcList) throws LensException {
    List<Candidate> candidateSet = new ArrayList<>();
    if (!cubeql.getCandidates().isEmpty()) {
      // All Candidates
      List<Candidate> allCandidates = new ArrayList<>(cubeql.getCandidates());
      // Partially valid candidates
      List<Candidate> allCandidatesPartiallyValid = new ArrayList<>();
      for (Candidate cand : allCandidates) {
        // Assuming initial list of candidates populated are StorageCandidate
        assert (cand instanceof StorageCandidate);
        StorageCandidate sc = (StorageCandidate) cand;
        if (CandidateUtil.isValidForTimeRanges(sc, cubeql.getTimeRanges())) {
          candidateSet.add(CandidateUtil.cloneStorageCandidate(sc));
        } else if (CandidateUtil.isPartiallyValidForTimeRanges(sc, cubeql.getTimeRanges())) {
          allCandidatesPartiallyValid.add(CandidateUtil.cloneStorageCandidate(sc));
        } else {
          cubeql.addCandidatePruningMsg(sc, CandidateTablePruneCause.storageNotAvailableInRange(
              cubeql.getTimeRanges()));
        }

      }
      // Get all covering fact sets
      List<UnionCandidate> unionCoveringSet =
          getCombinations(new ArrayList<>(allCandidatesPartiallyValid), cubeql);
      // Sort the Collection based on no of elements
      unionCoveringSet.sort(new CandidateUtil.ChildrenSizeBasedCandidateComparator<UnionCandidate>());
      // prune non covering sets
      pruneUnionCandidatesNotCoveringAllRanges(unionCoveringSet, cubeql);
      // prune candidate set which doesn't contain any common measure i
      pruneUnionCoveringSetWithoutAnyCommonMeasure(unionCoveringSet, queriedMsrs, cubeql);
      // prune redundant covering sets
      pruneRedundantUnionCoveringSets(unionCoveringSet);
      // pruing done in the previous steps, now create union candidates
      candidateSet.addAll(unionCoveringSet);
      updateQueriableMeasures(candidateSet, qpcList, cubeql);
    }
    return candidateSet;
  }

  private boolean isMeasureAnswerablebyUnionCandidate(QueriedPhraseContext msr, Candidate uc,
      CubeQueryContext cubeql) throws LensException {
    // Candidate is a single StorageCandidate
    if ((uc instanceof StorageCandidate) && !msr.isEvaluable(cubeql, (StorageCandidate) uc)) {
      return false;
    } else if ((uc instanceof UnionCandidate)){
      for (Candidate cand : uc.getChildren()) {
        if (!msr.isEvaluable(cubeql, (StorageCandidate) cand)) {
          return false;
        }
      }
    }
    return true;
  }

  private void pruneUnionCoveringSetWithoutAnyCommonMeasure(List<UnionCandidate> ucs,
      Set<QueriedPhraseContext> queriedMsrs,
      CubeQueryContext cubeql) throws LensException {
    for (ListIterator<UnionCandidate> itr = ucs.listIterator(); itr.hasNext();) {
      boolean toRemove = true;
      UnionCandidate uc = itr.next();
      for (QueriedPhraseContext msr : queriedMsrs) {
        if (isMeasureAnswerablebyUnionCandidate(msr, uc, cubeql)) {
          toRemove = false;
          break;
        }
      }
      if (toRemove) {
        itr.remove();
      }
    }
  }

  private void pruneRedundantUnionCoveringSets(List<UnionCandidate> candidates) {
    for (int i = 0; i < candidates.size(); i++) {
      UnionCandidate current = candidates.get(i);
      int j = i + 1;
      for (ListIterator<UnionCandidate> itr = candidates.listIterator(j); itr.hasNext();) {
        UnionCandidate next = itr.next();
        if (next.getChildren().containsAll(current.getChildren())) {
          itr.remove();
        }
      }
    }
  }

  private List<UnionCandidate> getCombinations(final List<Candidate> candidates, CubeQueryContext cubeql) {
    List<UnionCandidate> combinations = new LinkedList<>();
    int size = candidates.size();
    int threshold = Double.valueOf(Math.pow(2, size)).intValue() - 1;

    for (int i = 1; i <= threshold; ++i) {
      LinkedList<Candidate> individualCombinationList = new LinkedList<>();
      int count = size - 1;
      int clonedI = i;
      while (count >= 0) {
        if ((clonedI & 1) != 0) {
          individualCombinationList.addFirst(candidates.get(count));
        }
        clonedI = clonedI >>> 1;
        --count;
      }
      combinations.add(new UnionCandidate(individualCombinationList, cubeql));
    }
    return combinations;
  }

  private List<List<Candidate>> resolveJoinCandidates(List<Candidate> unionCandidates,
      Set<QueriedPhraseContext> msrs, CubeQueryContext cubeql) throws LensException {
    List<List<Candidate>> msrCoveringSets = new ArrayList<>();
    List<Candidate> ucSet = new ArrayList<>(unionCandidates);
    // Check if a single set can answer all the measures and exprsWithMeasures
    for (Iterator<Candidate> i = ucSet.iterator(); i.hasNext();) {
      boolean evaluable = false;
      Candidate uc = i.next();
      for (QueriedPhraseContext msr : msrs) {
        evaluable = isMeasureAnswerablebyUnionCandidate(msr, uc, cubeql);
        if (!evaluable) {
          break;
        }
      }
      if (evaluable) {
        // single set can answer all the measures as an UnionCandidate
        List<Candidate> one = new ArrayList<>();
        one.add(uc);
        msrCoveringSets.add(one);
        i.remove();
      }
    }
    // Sets that contain all measures or no measures are removed from iteration.
    // find other facts
    for (Iterator<Candidate> i = ucSet.iterator(); i.hasNext();) {
      Candidate candidate = i.next();
      i.remove();
      // find the remaining measures in other facts
      if (i.hasNext()) {
        Set<QueriedPhraseContext> remainingMsrs = new HashSet<>(msrs);
        Set<QueriedPhraseContext> coveredMsrs = CandidateUtil.coveredMeasures(candidate, msrs, cubeql);
        remainingMsrs.removeAll(coveredMsrs);

        List<List<Candidate>> coveringSets = resolveJoinCandidates(ucSet, remainingMsrs, cubeql);
        if (!coveringSets.isEmpty()) {
          for (List<Candidate> candSet : coveringSets) {
            candSet.add(candidate);
            msrCoveringSets.add(candSet);
          }
        } else {
          log.info("Couldnt find any set containing remaining measures:{} {} in {}", remainingMsrs,
              ucSet);
        }
      }
    }
    log.info("Covering set {} for measures {} with factsPassed {}", msrCoveringSets, msrs, ucSet);
    return msrCoveringSets;
  }

  private void updateQueriableMeasures(List<Candidate> cands,
      List<QueriedPhraseContext> qpcList, CubeQueryContext cubeql) throws LensException {
    for (Candidate cand : cands) {
      updateStorageCandidateQueriableMeasures(cand, qpcList, cubeql);
    }
  }


  private void updateStorageCandidateQueriableMeasures(Candidate unionCandidate,
      List<QueriedPhraseContext> qpcList, CubeQueryContext cubeql) throws LensException {
    QueriedPhraseContext msrPhrase;
    boolean isEvaluable;
    for (int index = 0; index < qpcList.size(); index++) {

      if (!qpcList.get(index).hasMeasures(cubeql)) {
        //Not a measure phrase. Skip it
        continue;
      }

      msrPhrase = qpcList.get(index);
      if (unionCandidate instanceof StorageCandidate && msrPhrase.isEvaluable(cubeql,
          (StorageCandidate) unionCandidate)) {
        ((StorageCandidate) unionCandidate).setAnswerableMeasurePhraseIndices(index);
      } else if (unionCandidate instanceof UnionCandidate) {
        isEvaluable = true;
        for (Candidate childCandidate : unionCandidate.getChildren()) {
          if (!msrPhrase.isEvaluable(cubeql, (StorageCandidate) childCandidate)) {
            isEvaluable = false;
            break;
          }
        }
        if (isEvaluable) {
          //Set the index for all the children in this case
          for (Candidate childCandidate : unionCandidate.getChildren()) {
            ((StorageCandidate) childCandidate).setAnswerableMeasurePhraseIndices(index);
          }
        }
      }
    }
  }
}
