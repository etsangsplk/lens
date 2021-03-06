/**
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
 * KIND, either express or implied.  See the License for theJoinCandidate.java
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.cube.parse;

import static org.apache.hadoop.hive.ql.parse.HiveParser.Identifier;

import java.util.*;

import org.apache.lens.cube.metadata.*;
import org.apache.lens.server.api.error.LensException;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

import org.antlr.runtime.CommonToken;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

/**
 * Placeholder for Util methods that will be required for {@link Candidate}
 */
public final class CandidateUtil {

  private CandidateUtil() {
    // Added due to checkstyle error getting below :
    // (design) HideUtilityClassConstructor: Utility classes should not have a public or default constructor.
  }

  /**
   * Returns true if the Candidate is valid for all the timeranges based on its start and end times.
   * @param candidate
   * @param timeRanges
   * @return
   */
  public static boolean isValidForTimeRanges(Candidate candidate, List<TimeRange> timeRanges) {
    for (TimeRange timeRange : timeRanges) {
      if (!(timeRange.getFromDate().after(candidate.getStartTime())
          && timeRange.getToDate().before(candidate.getEndTime()))) {
        return false;
      }
    }
    return true;
  }

  static boolean isCandidatePartiallyValidForTimeRange(Date candidateStartTime, Date candidateEndTime,
    Date timeRangeStart, Date timeRangeEnd) {
    Date start  = candidateStartTime.after(timeRangeStart) ? candidateStartTime : timeRangeStart;
    Date end = candidateEndTime.before(timeRangeEnd) ? candidateEndTime : timeRangeEnd;
    if (end.after(start)) {
      return true;
    }
    return false;
  }


  static boolean isPartiallyValidForTimeRange(Candidate cand, TimeRange timeRange) {
    return isPartiallyValidForTimeRanges(cand, Arrays.asList(timeRange));
  }

  static boolean isPartiallyValidForTimeRanges(Candidate cand, List<TimeRange> timeRanges) {
    return timeRanges.stream().anyMatch(timeRange ->
      isCandidatePartiallyValidForTimeRange(cand.getStartTime(), cand.getEndTime(),
        timeRange.getFromDate(), timeRange.getToDate()));
  }

  /**
   * Copy Query AST from sourceAst to targetAst
   *
   * @param sourceAst
   * @param targetAst
   * @throws LensException
   */
  static void copyASTs(QueryAST sourceAst, QueryAST targetAst) throws LensException {

    targetAst.setSelectAST(MetastoreUtil.copyAST(sourceAst.getSelectAST()));
    targetAst.setWhereAST(MetastoreUtil.copyAST(sourceAst.getWhereAST()));
    if (sourceAst.getJoinAST() != null) {
      targetAst.setJoinAST(MetastoreUtil.copyAST(sourceAst.getJoinAST()));
    }
    if (sourceAst.getGroupByAST() != null) {
      targetAst.setGroupByAST(MetastoreUtil.copyAST(sourceAst.getGroupByAST()));
    }
    if (sourceAst.getHavingAST() != null) {
      targetAst.setHavingAST(MetastoreUtil.copyAST(sourceAst.getHavingAST()));
    }
    if (sourceAst.getOrderByAST() != null) {
      targetAst.setOrderByAST(MetastoreUtil.copyAST(sourceAst.getOrderByAST()));
    }

    targetAst.setLimitValue(sourceAst.getLimitValue());
    targetAst.setFromString(sourceAst.getFromString());
    targetAst.setWhereString(sourceAst.getWhereString());
  }

  public static Set<StorageCandidate> getStorageCandidates(final Candidate candidate) {
    return getStorageCandidates(new HashSet<Candidate>(1) {{ add(candidate); }});
  }

  // this function should only be used for union candidates and never for join candidates.
  // future scope of improvement: move the data model to use polymorphism
  static Set<QueriedPhraseContext> coveredMeasures(Candidate candSet, Collection<QueriedPhraseContext> msrs,
    CubeQueryContext cubeql) throws LensException {
    Set<QueriedPhraseContext> coveringSet = new HashSet<>();
    for (QueriedPhraseContext msr : msrs) {
      if (candSet.getChildren() == null) {
        if (msr.isEvaluable(cubeql, (StorageCandidate) candSet)) {
          coveringSet.add(msr);
        }
      } else {
        boolean allCanAnswer = true;
        for (Candidate cand : candSet.getChildren()) {
          if (!msr.isEvaluable(cubeql, (StorageCandidate) cand)) {
            allCanAnswer = false;
            break;
          }
        }
        if (allCanAnswer) {
          coveringSet.add(msr);
        }
      }
    }
    return coveringSet;
  }

  /**
   * Returns true is the Candidates cover the entire time range.
   * @param candidates
   * @param startTime
   * @param endTime
   * @return
   */
  public static boolean isTimeRangeCovered(Collection<Candidate> candidates, Date startTime, Date endTime) {
    RangeSet<Date> set = TreeRangeSet.create();
    for (Candidate candidate : candidates) {
      set.add(Range.range(candidate.getStartTime(), BoundType.CLOSED, candidate.getEndTime(), BoundType.OPEN));
    }
    return set.encloses(Range.range(startTime, BoundType.CLOSED, endTime, BoundType.OPEN));
  }

  public static Set<String> getColumns(Collection<QueriedPhraseContext> queriedPhraseContexts) {
    Set<String> cols = new HashSet<>();
    for (QueriedPhraseContext qur : queriedPhraseContexts) {
      cols.addAll(qur.getColumns());
    }
    return cols;
  }

  /**
   * Filters Candidates that contain the filterCandidate
   *
   * @param candidates
   * @param filterCandidate
   * @return pruned Candidates
   */
  public static Collection<Candidate> filterCandidates(Collection<Candidate> candidates, Candidate filterCandidate) {
    List<Candidate> prunedCandidates = new ArrayList<>();
    Iterator<Candidate> itr = candidates.iterator();
    while (itr.hasNext()) {
      if (itr.next().contains(filterCandidate)) {
        prunedCandidates.add(itr.next());
        itr.remove();
      }
    }
    return prunedCandidates;
  }

  /**
   * Gets all the Storage Candidates that participate in the collection of passed candidates
   *
   * @param candidates
   * @return
   */
  public static Set<StorageCandidate> getStorageCandidates(Collection<Candidate> candidates) {
    Set<StorageCandidate> storageCandidateSet = new HashSet<>();
    getStorageCandidates(candidates, storageCandidateSet);
    return storageCandidateSet;
  }

  private static void getStorageCandidates(Collection<Candidate> candidates,
    Set<StorageCandidate> storageCandidateSet) {
    for (Candidate candidate : candidates) {
      if (candidate.getChildren() == null) {
        //Expecting this to be a StorageCandidate as it has no children.
        storageCandidateSet.add((StorageCandidate)candidate);
      } else {
        getStorageCandidates(candidate.getChildren(), storageCandidateSet);
      }
    }
  }

  public static StorageCandidate cloneStorageCandidate(StorageCandidate sc) throws LensException{
    return new StorageCandidate(sc);
  }

  public static boolean factHasColumn(CubeFactTable fact, String column) {
    for (FieldSchema factField : fact.getColumns()) {
      if (factField.getName().equals(column)) {
        return true;
      }
    }
    return false;
  }

  public static String getTimeRangeWhereClasue(TimeRangeWriter rangeWriter,
      StorageCandidate sc, TimeRange range) throws LensException {
    String rangeWhere = rangeWriter.getTimeRangeWhereClause(sc.getCubeql(),
        sc.getCubeql().getAliasForTableName(sc.getCube().getName()),
      sc.getRangeToPartitions().get(range));
    if (sc.getRangeToExtraWhereFallBack().containsKey(range)) {
      rangeWhere =  "((" + rangeWhere + ") and  (" + sc.getRangeToExtraWhereFallBack().get(range) + "))";
    }
    return rangeWhere;
  }

  public static class ChildrenSizeBasedCandidateComparator<T> implements Comparator<Candidate> {
    @Override
    public int compare(Candidate o1, Candidate o2) {
      return o1.getChildren().size() - o2.getChildren().size();
    }
  }

  private static final String BASE_QUERY_FORMAT = "SELECT %s FROM %s";

  public static String buildHQLString(String select, String from, String where,
      String groupby, String orderby, String having, Integer limit) {
    List<String> qstrs = new ArrayList<String>();
    qstrs.add(select);
    qstrs.add(from);
    if (!StringUtils.isBlank(where)) {
      qstrs.add(where);
    }
    if (!StringUtils.isBlank(groupby)) {
      qstrs.add(groupby);
    }
    if (!StringUtils.isBlank(having)) {
      qstrs.add(having);
    }
    if (!StringUtils.isBlank(orderby)) {
      qstrs.add(orderby);
    }
    if (limit != null) {
      qstrs.add(String.valueOf(limit));
    }

    StringBuilder queryFormat = new StringBuilder();
    queryFormat.append(BASE_QUERY_FORMAT);
    if (!StringUtils.isBlank(where)) {
      queryFormat.append(" WHERE %s");
    }
    if (!StringUtils.isBlank(groupby)) {
      queryFormat.append(" GROUP BY %s");
    }
    if (!StringUtils.isBlank(having)) {
      queryFormat.append(" HAVING %s");
    }
    if (!StringUtils.isBlank(orderby)) {
      queryFormat.append(" ORDER BY %s");
    }
    if (limit != null) {
      queryFormat.append(" LIMIT %s");
    }
    return String.format(queryFormat.toString(), qstrs.toArray(new String[qstrs.size()]));
  }

  /**
   *
   * @param selectAST Outer query selectAST
   * @param cubeql Cubequery Context
   *
   *  Update the final alias in the outer select expressions
   *  1. Replace queriedAlias with finalAlias if both are not same
   *  2. If queriedAlias is missing add finalAlias as alias
   */
  public static void updateFinalAlias(ASTNode selectAST, CubeQueryContext cubeql) {
    for (int i = 0; i < selectAST.getChildCount(); i++) {
      ASTNode selectExpr = (ASTNode) selectAST.getChild(i);
      ASTNode aliasNode = HQLParser.findNodeByPath(selectExpr, Identifier);
      String finalAlias = cubeql.getSelectPhrases().get(i).getFinalAlias().replaceAll("`", "");
      if (aliasNode != null) {
        String queryAlias = aliasNode.getText();
        if (!queryAlias.equals(finalAlias)) {
          // replace the alias node
          ASTNode newAliasNode = new ASTNode(new CommonToken(HiveParser.Identifier, finalAlias));
          selectAST.getChild(i).replaceChildren(selectExpr.getChildCount() - 1,
              selectExpr.getChildCount() - 1, newAliasNode);
        }
      } else {
        // add column alias
        ASTNode newAliasNode = new ASTNode(new CommonToken(HiveParser.Identifier, finalAlias));
        selectAST.getChild(i).addChild(newAliasNode);
      }
    }
  }




}
