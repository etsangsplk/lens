/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.cube.parse;

import static org.apache.lens.cube.parse.CandidateTablePruneCause.CandidateTablePruneCode;
import static org.apache.lens.cube.parse.CandidateTablePruneCause.SkipUpdatePeriodCode;
import static org.apache.lens.cube.parse.CandidateTablePruneCause.timeDimNotSupported;
import static org.apache.lens.cube.parse.StorageUtil.getFallbackRange;
import static org.apache.lens.cube.parse.StorageUtil.joinWithAnd;
import static org.apache.lens.cube.parse.StorageUtil.processCubeColForDataCompleteness;
import static org.apache.lens.cube.parse.StorageUtil.processExpressionsForCompleteness;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.lens.cube.metadata.AbstractCubeTable;
import org.apache.lens.cube.metadata.CubeFactTable;
import org.apache.lens.cube.metadata.CubeInterface;
import org.apache.lens.cube.metadata.CubeMetastoreClient;
import org.apache.lens.cube.metadata.DateUtil;
import org.apache.lens.cube.metadata.Dimension;
import org.apache.lens.cube.metadata.FactPartition;
import org.apache.lens.cube.metadata.MetastoreUtil;
import org.apache.lens.cube.metadata.TimeRange;
import org.apache.lens.cube.metadata.UpdatePeriod;
import org.apache.lens.server.api.error.LensException;
import org.apache.lens.server.api.metastore.DataCompletenessChecker;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.session.SessionState;

import org.antlr.runtime.CommonToken;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents a fact on a storage table and the dimensions it needs to be joined with to answer the query
 */
@Slf4j
public class StorageCandidate implements Candidate, CandidateTable {

  // TODO union : Put comments on member variables.
  @Getter
  private final CubeQueryContext cubeql;
  private final String processTimePartCol;
  private final CubeMetastoreClient client;
  private final String completenessPartCol;
  private final float completenessThreshold;

  /**
   * Name of this storage candidate  = storageName_factName
   */
  @Getter
  @Setter
  private String name;

  /**
   * This is the storage table specific name. It is used while generating query from this candidate
   */
  @Setter
  private String resolvedName;
  /**
   * Valid update periods populated by Phase 1.
   */
  @Getter
  private TreeSet<UpdatePeriod> validUpdatePeriods = new TreeSet<>();

  /**
   * These are the update periods that finally participate in partitions.
   * @see #getParticipatingPartitions()
   */
  @Getter
  private TreeSet<UpdatePeriod> participatingUpdatePeriods = new TreeSet<>();

  @Getter
  @Setter
  Map<String, SkipUpdatePeriodCode> updatePeriodRejectionCause;
  private Configuration conf = null;

  /**
   * This map holds Tags (A tag refers to one or more measures) that have incomplete (below configured threshold) data.
   * Value is a map of date string and %completeness.
   */
  @Getter
  private Map<String, Map<String, Float>> dataCompletenessMap = new HashMap<>();
  private SimpleDateFormat partWhereClauseFormat = null;
  /**
   * Participating fact, storage and dimensions for this StorageCandidate
   */
  @Getter
  private CubeFactTable fact;
  @Getter
  private String storageName;
  @Getter
  private String storageTable;
  @Getter
  @Setter
  private QueryAST queryAst;
  @Getter
  private Map<TimeRange, Set<FactPartition>> rangeToPartitions = new LinkedHashMap<>();
  @Getter
  private Map<TimeRange, String> rangeToExtraWhereFallBack = new LinkedHashMap<>();
  @Getter
  @Setter
  private String whereString;
  @Getter
  private Set<Integer> answerableMeasurePhraseIndices = Sets.newHashSet();
  @Getter
  @Setter
  private String fromString;
  @Getter
  private CubeInterface cube;
  @Getter
  private Map<Dimension, CandidateDim> dimsToQuery;
  @Getter
  private Date startTime;
  @Getter
  private Date endTime;
  /**
   * Cached fact columns
   */
  private Collection<String> factColumns;

  /**
   * Non existing partitions
   */
  @Getter
  private Set<String> nonExistingPartitions = new HashSet<>();

  /**
   * This will be true if this storage candidate has multiple storage tables (one per update period)
   * https://issues.apache.org/jira/browse/LENS-1386
   */
  @Getter
  private boolean isStorageTblsAtUpdatePeriodLevel;

  @Getter
  private int numQueriedParts = 0;

  public StorageCandidate(StorageCandidate sc) throws LensException {
    this(sc.getCube(), sc.getFact(), sc.getStorageName(), sc.getCubeql());
    this.validUpdatePeriods.addAll(sc.getValidUpdatePeriods());
    this.whereString = sc.whereString;
    this.fromString = sc.fromString;
    this.dimsToQuery = sc.dimsToQuery;
    this.factColumns = sc.factColumns;
    this.answerableMeasurePhraseIndices.addAll(sc.answerableMeasurePhraseIndices);
    if (sc.getQueryAst() != null) {
      this.queryAst = new DefaultQueryAST();
      CandidateUtil.copyASTs(sc.getQueryAst(), new DefaultQueryAST());
    }
    for (Map.Entry<TimeRange, Set<FactPartition>> entry : sc.getRangeToPartitions().entrySet()) {
      rangeToPartitions.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
    }
    this.rangeToExtraWhereFallBack = sc.rangeToExtraWhereFallBack;
    this.answerableMeasurePhraseIndices = sc.answerableMeasurePhraseIndices;
  }

  public StorageCandidate(CubeInterface cube, CubeFactTable fact, String storageName, CubeQueryContext cubeql)
    throws LensException {
    if ((cube == null) || (fact == null) || (storageName == null)) {
      throw new IllegalArgumentException("Cube,fact and storageName should be non null");
    }
    this.cube = cube;
    this.fact = fact;
    this.cubeql = cubeql;
    this.storageName = storageName;
    this.storageTable = MetastoreUtil.getFactOrDimtableStorageTableName(fact.getName(), storageName);
    this.conf = cubeql.getConf();
    this.name = fact.getName();
    this.processTimePartCol = conf.get(CubeQueryConfUtil.PROCESS_TIME_PART_COL);
    String formatStr = conf.get(CubeQueryConfUtil.PART_WHERE_CLAUSE_DATE_FORMAT);
    if (formatStr != null) {
      this.partWhereClauseFormat = new SimpleDateFormat(formatStr);
    }
    completenessPartCol = conf.get(CubeQueryConfUtil.COMPLETENESS_CHECK_PART_COL);
    completenessThreshold = conf
      .getFloat(CubeQueryConfUtil.COMPLETENESS_THRESHOLD, CubeQueryConfUtil.DEFAULT_COMPLETENESS_THRESHOLD);
    client = cubeql.getMetastoreClient();
    Set<String> storageTblNames = client.getStorageTables(fact.getName(), storageName);
    if (storageTblNames.size() > 1) {
      isStorageTblsAtUpdatePeriodLevel = true;
    } else {
      //if this.storageTable is equal to the storage table name it implies isStorageTblsAtUpdatePeriodLevel is false
      isStorageTblsAtUpdatePeriodLevel = !storageTblNames.iterator().next().equalsIgnoreCase(storageTable);
    }
    setStorageStartAndEndDate();
  }

  /**
   * Sets Storage candidates start and end time based on underlying storage-tables
   *
   * CASE 1
   * If has Storage has single storage table*
   * Storage start time = max(storage start time , fact start time)
   * Storage end time = min(storage end time , fact start time)
   *
   * CASE 2
   * If the Storage has multiple Storage Tables (one per update period)*
   * update Period start Time = Max(update start time, fact start time)
   * update Period end Time = Min(update end time, fact end time)
   * Stoarge start and end time is derived form the underlying update period start and end times.
   * Storage start time = min(update1 start time ,...., updateN start time)
   * Storage end time = max(update1 end time ,...., updateN end time)
   *
   * Note in Case 2 its assumed that the time range supported by different update periods are either
   * overlapping(Example 2) or form a non overlapping but continuous chain(Example 1) as illustrated
   * in examples below
   *
   * Example 1
   * A Storage has 2 Non Oevralpping but continuous Update Periods.
   * MONTHLY with start time as now.month -13 months and end time as now.month -2months  and
   * DAILY with start time as now.day and end time as now.month -2months
   * Then this Sorage will have an implied start time as now.month -13 month and end time as now.day
   *
   * Example 2
   * A Storage has 2 Overlapping Update Periods.
   * MONTHLY with start time as now.month -13 months and end time as now.month -1months  and
   * DAILY with start time as now.day and end time as now.month -2months
   * Then this Sorage will have an implied start time as now.month -13 month and end time as now.day
   *
   * @throws LensException
   */
  public void setStorageStartAndEndDate() throws LensException {
    if (this.startTime != null && !this.isStorageTblsAtUpdatePeriodLevel) {
      //If the times are already set and are not dependent of update period, no point setting times again.
      return;
    }
    List<Date> startDates = new ArrayList<>();
    List<Date> endDates = new ArrayList<>();
    for (String storageTablePrefix : getValidStorageTableNames()) {
      startDates.add(client.getStorageTableStartDate(storageTablePrefix, fact.getName()));
      endDates.add(client.getStorageTableEndDate(storageTablePrefix, fact.getName()));
    }
    this.startTime = Collections.min(startDates);
    this.endTime = Collections.max(endDates);
  }

  private Set<String> getValidStorageTableNames() throws LensException {
    if (!validUpdatePeriods.isEmpty()) {
      // In this case skip invalid update periods and get storage tables only for valid ones.
      Set<String> uniqueStorageTables = new HashSet<>();
      for (UpdatePeriod updatePeriod : validUpdatePeriods) {
        uniqueStorageTables.add(client.getStorageTableName(fact.getName(), storageName, updatePeriod));
      }
      return uniqueStorageTables;
    } else {
      //Get all storage tables.
      return client.getStorageTables(fact.getName(), storageName);
    }
  }

  private void setMissingExpressions(Set<Dimension> queriedDims) throws LensException {
    setFromString(String.format("%s", getFromTable()));
    setWhereString(joinWithAnd(
      genWhereClauseWithDimPartitions(whereString, queriedDims), cubeql.getConf().getBoolean(
        CubeQueryConfUtil.REPLACE_TIMEDIM_WITH_PART_COL, CubeQueryConfUtil.DEFAULT_REPLACE_TIMEDIM_WITH_PART_COL)
        ? getPostSelectionWhereClause() : null));
    if (cubeql.getHavingAST() != null) {
      queryAst.setHavingAST(MetastoreUtil.copyAST(cubeql.getHavingAST()));
    }
  }

  private String genWhereClauseWithDimPartitions(String originalWhere, Set<Dimension> queriedDims) {
    StringBuilder whereBuf;
    if (originalWhere != null) {
      whereBuf = new StringBuilder(originalWhere);
    } else {
      whereBuf = new StringBuilder();
    }

    // add where clause for all dimensions
    if (cubeql != null) {
      boolean added = (originalWhere != null);
      for (Dimension dim : queriedDims) {
        CandidateDim cdim = dimsToQuery.get(dim);
        String alias = cubeql.getAliasForTableName(dim.getName());
        if (!cdim.isWhereClauseAdded() && !StringUtils.isBlank(cdim.getWhereClause())) {
          appendWhereClause(whereBuf, StorageUtil.getWhereClause(cdim, alias), added);
          added = true;
        }
      }
    }
    if (whereBuf.length() == 0) {
      return null;
    }
    return whereBuf.toString();
  }

  private static void appendWhereClause(StringBuilder filterCondition, String whereClause, boolean hasMore) {
    // Make sure we add AND only when there are already some conditions in where
    // clause
    if (hasMore && !filterCondition.toString().isEmpty() && !StringUtils.isBlank(whereClause)) {
      filterCondition.append(" AND ");
    }

    if (!StringUtils.isBlank(whereClause)) {
      filterCondition.append("(");
      filterCondition.append(whereClause);
      filterCondition.append(")");
    }
  }

  private String getPostSelectionWhereClause() throws LensException {
    return null;
  }

  void setAnswerableMeasurePhraseIndices(int index) {
    answerableMeasurePhraseIndices.add(index);
  }

  public String toHQL(Set<Dimension> queriedDims) throws LensException {
    setMissingExpressions(queriedDims);
    // Check if the picked candidate is a StorageCandidate and in that case
    // update the selectAST with final alias.
    if (this == cubeql.getPickedCandidate()) {
      CandidateUtil.updateFinalAlias(queryAst.getSelectAST(), cubeql);
      updateOrderByWithFinalAlias(queryAst.getOrderByAST(), queryAst.getSelectAST());
    } else {
      queryAst.setHavingAST(null);
    }
    return CandidateUtil
      .buildHQLString(queryAst.getSelectString(), fromString, whereString, queryAst.getGroupByString(),
        queryAst.getOrderByString(), queryAst.getHavingString(), queryAst.getLimitValue());
  }

  /**
   * Update Orderby children with final alias used in select
   *
   * @param orderby Order by AST
   * @param select  Select AST
   */
  private void updateOrderByWithFinalAlias(ASTNode orderby, ASTNode select) {
    if (orderby == null) {
      return;
    }
    for (Node orderbyNode : orderby.getChildren()) {
      ASTNode orderBychild = (ASTNode) orderbyNode;
      for (Node selectNode : select.getChildren()) {
        ASTNode selectChild = (ASTNode) selectNode;
        if (selectChild.getChildCount() == 2) {
          if (HQLParser.getString((ASTNode) selectChild.getChild(0))
            .equals(HQLParser.getString((ASTNode) orderBychild.getChild(0)))) {
            ASTNode alias = new ASTNode((ASTNode) selectChild.getChild(1));
            orderBychild.replaceChildren(0, 0, alias);
            break;
          }
        }
      }
    }
  }

  @Override
  public String getStorageString(String alias) {
    return storageName + " " + alias;
  }

  @Override
  public AbstractCubeTable getTable() {
    return fact;
  }

  @Override
  public AbstractCubeTable getBaseTable() {
    return (AbstractCubeTable) cube;
  }

  @Override
  public Collection<String> getColumns() {
    if (factColumns == null) {
      factColumns = fact.getValidColumns();
      if (factColumns == null) {
        factColumns = fact.getAllFieldNames();
      }
    }
    return factColumns;
  }

  @Override
  public double getCost() {
    return fact.weight();
  }

  @Override
  public boolean contains(Candidate candidate) {
    return this.equals(candidate);
  }

  @Override
  public Collection<Candidate> getChildren() {
    return null;
  }

  private void updatePartitionStorage(FactPartition part) throws LensException {
    try {
      if (client.factPartitionExists(fact, part, storageTable)) {
        part.getStorageTables().add(storageTable);
        part.setFound(true);
      }
    } catch (HiveException e) {
      log.warn("Hive exception while getting storage table partition", e);
    }
  }

  /**
   * Gets FactPartitions for the given fact using the following logic
   *
   * 1. Find the max update interval that will be used for the query. Lets assume time
   * range is 15 Sep to 15 Dec and the fact has two storage with update periods as MONTHLY,DAILY,HOURLY.
   * In this case the data for [15 sep - 1 oct)U[1 Dec - 15 Dec) will be answered by DAILY partitions
   * and [1 oct - 1Dec) will be answered by MONTHLY partitions. The max interavl for this query will be MONTHLY.
   *
   * 2.Prune Storgaes that do not fall in the queries time range.
   * {@link CubeMetastoreClient#isStorageTableCandidateForRange(String, Date, Date)}
   *
   * 3. Iterate over max interavl . In out case it will give two months Oct and Nov. Find partitions for
   * these two months.Check validity of FactPartitions for Oct and Nov
   * via {@link #updatePartitionStorage(FactPartition)}.
   * If the partition is missing, try getting partitions for the time range form other update periods (DAILY,HOURLY).
   * This is achieved by calling getPartitions() recursively but passing only 2 update periods (DAILY,HOURLY)
   *
   * 4.If the monthly partitions are found, check for lookahead partitions and call getPartitions recursively for the
   * remaining time intervals i.e, [15 sep - 1 oct) and [1 Dec - 15 Dec)
   *
   * TODO union : Move this into util.
   */
  private boolean getPartitions(Date fromDate, Date toDate, String partCol, Set<FactPartition> partitions,
    TreeSet<UpdatePeriod> updatePeriods, boolean addNonExistingParts, boolean failOnPartialData,
    PartitionRangesForPartitionColumns missingPartitions) throws LensException {
    if (fromDate.equals(toDate) || fromDate.after(toDate)) {
      return true;
    }
    if (updatePeriods == null | updatePeriods.isEmpty()) {
      return false;
    }

    UpdatePeriod maxInterval = CubeFactTable.maxIntervalInRange(fromDate, toDate, updatePeriods);
    if (maxInterval == null) {
      log.info("No max interval for range: {} to {}", fromDate, toDate);
      return false;
    }

    if (maxInterval == UpdatePeriod.CONTINUOUS
      && cubeql.getRangeWriter().getClass().equals(BetweenTimeRangeWriter.class)) {
      FactPartition part = new FactPartition(partCol, fromDate, maxInterval, null, partWhereClauseFormat);
      partitions.add(part);
      part.getStorageTables().add(storageName);
      part = new FactPartition(partCol, toDate, maxInterval, null, partWhereClauseFormat);
      partitions.add(part);
      part.getStorageTables().add(storageName);
      this.participatingUpdatePeriods.add(maxInterval);
      log.info("Added continuous fact partition for storage table {}", storageName);
      return true;
    }

    if (!client.partColExists(this.getFact().getName(), storageName, partCol)) {
      log.info("{} does not exist in {}", partCol, storageTable);
      return false;
    }

    Date maxIntervalStorageTblStartDate = getStorageTableStartDate(maxInterval);
    Date maxIntervalStorageTblEndDate = getStorageTableEndDate(maxInterval);

    TreeSet<UpdatePeriod> remainingIntervals =  new TreeSet<>(updatePeriods);
    remainingIntervals.remove(maxInterval);
    if (!CandidateUtil.isCandidatePartiallyValidForTimeRange(
      maxIntervalStorageTblStartDate, maxIntervalStorageTblEndDate, fromDate, toDate)) {
      //Check the time range in remainingIntervals as maxInterval is not useful
      return getPartitions(fromDate, toDate, partCol, partitions, remainingIntervals,
        addNonExistingParts, failOnPartialData, missingPartitions);
    }

    Date ceilFromDate = DateUtil.getCeilDate(fromDate.after(maxIntervalStorageTblStartDate)
      ? fromDate : maxIntervalStorageTblStartDate, maxInterval);
    Date floorToDate = DateUtil.getFloorDate(toDate.before(maxIntervalStorageTblEndDate)
      ? toDate : maxIntervalStorageTblEndDate, maxInterval);
    if (ceilFromDate.equals(floorToDate) || floorToDate.before(ceilFromDate)) {
      return getPartitions(fromDate, toDate, partCol, partitions, remainingIntervals,
        addNonExistingParts, failOnPartialData, missingPartitions);
    }

    int lookAheadNumParts = conf
      .getInt(CubeQueryConfUtil.getLookAheadPTPartsKey(maxInterval), CubeQueryConfUtil.DEFAULT_LOOK_AHEAD_PT_PARTS);
    TimeRange.Iterable.Iterator iter = TimeRange.iterable(ceilFromDate, floorToDate, maxInterval, 1).iterator();
    // add partitions from ceilFrom to floorTo
    while (iter.hasNext()) {
      Date dt = iter.next();
      Date nextDt = iter.peekNext();
      FactPartition part = new FactPartition(partCol, dt, maxInterval, null, partWhereClauseFormat);
      updatePartitionStorage(part);
      log.debug("Storage tables containing Partition {} are: {}", part, part.getStorageTables());
      if (part.isFound()) {
        log.debug("Adding existing partition {}", part);
        partitions.add(part);
        this.participatingUpdatePeriods.add(maxInterval);
        log.debug("Looking for look ahead process time partitions for {}", part);
        if (processTimePartCol == null) {
          log.debug("processTimePartCol is null");
        } else if (partCol.equals(processTimePartCol)) {
          log.debug("part column is process time col");
        } else if (updatePeriods.first().equals(maxInterval)) {
          log.debug("Update period is the least update period");
        } else if ((iter.getNumIters() - iter.getCounter()) > lookAheadNumParts) {
          // see if this is the part of the last-n look ahead partitions
          log.debug("Not a look ahead partition");
        } else {
          log.debug("Looking for look ahead process time partitions for {}", part);
          // check if finer partitions are required
          // final partitions are required if no partitions from
          // look-ahead
          // process time are present
          TimeRange.Iterable.Iterator processTimeIter = TimeRange.iterable(nextDt, lookAheadNumParts, maxInterval, 1)
            .iterator();
          while (processTimeIter.hasNext()) {
            Date pdt = processTimeIter.next();
            Date nextPdt = processTimeIter.peekNext();
            FactPartition processTimePartition = new FactPartition(processTimePartCol, pdt, maxInterval, null,
              partWhereClauseFormat);
            updatePartitionStorage(processTimePartition);
            if (processTimePartition.isFound()) {
              log.debug("Finer parts not required for look-ahead partition :{}", part);
            } else {
              log.debug("Looked ahead process time partition {} is not found", processTimePartition);
              TreeSet<UpdatePeriod> newset = new TreeSet<UpdatePeriod>();
              newset.addAll(updatePeriods);
              newset.remove(maxInterval);
              log.debug("newset of update periods:{}", newset);
              if (!newset.isEmpty()) {
                // Get partitions for look ahead process time
                log.debug("Looking for process time partitions between {} and {}", pdt, nextPdt);
                Set<FactPartition> processTimeParts = getPartitions(
                  TimeRange.getBuilder().fromDate(pdt).toDate(nextPdt).partitionColumn(processTimePartCol).build(),
                  newset, true, failOnPartialData, missingPartitions);
                log.debug("Look ahead partitions: {}", processTimeParts);
                TimeRange timeRange = TimeRange.getBuilder().fromDate(dt).toDate(nextDt).build();
                for (FactPartition pPart : processTimeParts) {
                  log.debug("Looking for finer partitions in pPart: {}", pPart);
                  for (Date date : timeRange.iterable(pPart.getPeriod(), 1)) {
                    FactPartition innerPart = new FactPartition(partCol, date, pPart.getPeriod(), pPart,
                      partWhereClauseFormat);
                    updatePartitionStorage(innerPart);
                    innerPart.setFound(pPart.isFound());
                    if (innerPart.isFound()) {
                      partitions.add(innerPart);
                    }
                  }
                  log.debug("added all sub partitions blindly in pPart: {}", pPart);
                }
              }
            }
          }
        }
      } else {
        log.info("Partition:{} does not exist in any storage table", part);
        if (!getPartitions(dt, nextDt, partCol, partitions, remainingIntervals, false, failOnPartialData,
          missingPartitions)) {
          log.debug("Adding non existing partition {}", part);
          if (addNonExistingParts) {
            // Add non existing partitions for all cases of whether we populate all non existing or not.
            this.participatingUpdatePeriods.add(maxInterval);
            missingPartitions.add(part);
            if (!failOnPartialData) {
              partitions.add(part);
              part.getStorageTables().add(storageName);
            }
          } else {
            log.info("No finer granualar partitions exist for {}", part);
            return false;
          }
        } else {
          log.debug("Finer granualar partitions added for {}", part);
        }
      }
    }

    return getPartitions(fromDate, ceilFromDate, partCol, partitions, remainingIntervals,
        addNonExistingParts, failOnPartialData, missingPartitions)
        && getPartitions(floorToDate, toDate, partCol, partitions, remainingIntervals,
        addNonExistingParts, failOnPartialData, missingPartitions);
  }

  @Override
  public boolean evaluateCompleteness(TimeRange timeRange, TimeRange queriedTimeRange, boolean failOnPartialData)
    throws LensException {
    // Check the measure tags.
    if (!evaluateMeasuresCompleteness(timeRange)) {
      log.info("Storage candidate:{} has partitions with incomplete data: {} for given ranges: {}", this,
        dataCompletenessMap, cubeql.getTimeRanges());
      if (failOnPartialData) {
        return false;
      }
    }
    PartitionRangesForPartitionColumns missingParts = new PartitionRangesForPartitionColumns();
    PruneCauses<StorageCandidate> storagePruningMsgs = cubeql.getStoragePruningMsgs();
    Set<String> unsupportedTimeDims = Sets.newHashSet();
    Set<String> partColsQueried = Sets.newHashSet();
    partColsQueried.add(timeRange.getPartitionColumn());
    StringBuilder extraWhereClauseFallback = new StringBuilder();
    Set<FactPartition> rangeParts = getPartitions(timeRange, validUpdatePeriods, true, failOnPartialData, missingParts);
    String partCol = timeRange.getPartitionColumn();
    boolean partColNotSupported = rangeParts.isEmpty();
    String storageTableName = getStorageTable();

    if (storagePruningMsgs.containsKey(this)) {
      List<CandidateTablePruneCause> causes = storagePruningMsgs.get(this);
      // Find the PART_COL_DOES_NOT_EXISTS
      for (CandidateTablePruneCause cause : causes) {
        if (cause.getCause().equals(CandidateTablePruneCode.PART_COL_DOES_NOT_EXIST)) {
          partColNotSupported &= cause.getNonExistantPartCols().contains(partCol);
        }
      }
    } else {
      partColNotSupported = false;
    }
    TimeRange prevRange = timeRange;
    String sep = "";
    while (rangeParts.isEmpty()) {
      String timeDim = cubeql.getBaseCube().getTimeDimOfPartitionColumn(partCol);
      if (partColNotSupported && !CandidateUtil.factHasColumn(getFact(), timeDim)) {
        unsupportedTimeDims.add(cubeql.getBaseCube().getTimeDimOfPartitionColumn(timeRange.getPartitionColumn()));
        break;
      }
      TimeRange fallBackRange = getFallbackRange(prevRange, this.getFact().getName(), cubeql);
      log.info("No partitions for range:{}. fallback range: {}", timeRange, fallBackRange);
      if (fallBackRange == null) {
        break;
      }
      partColsQueried.add(fallBackRange.getPartitionColumn());
      rangeParts = getPartitions(fallBackRange, validUpdatePeriods, true, failOnPartialData, missingParts);
      extraWhereClauseFallback.append(sep)
        .append(prevRange.toTimeDimWhereClause(cubeql.getAliasForTableName(cubeql.getCube()), timeDim));
      sep = " AND ";
      prevRange = fallBackRange;
      partCol = prevRange.getPartitionColumn();
      if (!rangeParts.isEmpty()) {
        break;
      }
    }
    // Add all the partitions. participatingPartitions contains all the partitions for previous time ranges also.
    rangeToPartitions.put(queriedTimeRange, rangeParts);
    numQueriedParts += rangeParts.size();
    if (!unsupportedTimeDims.isEmpty()) {
      log.info("Not considering storage candidate:{} as it doesn't support time dimensions: {}", this,
        unsupportedTimeDims);
      cubeql.addStoragePruningMsg(this, timeDimNotSupported(unsupportedTimeDims));
      return false;
    }
    Set<String> nonExistingParts = missingParts.toSet(partColsQueried);
    // TODO union : Relook at this.
    nonExistingPartitions.addAll(nonExistingParts);
    if (rangeParts.size() == 0 || (failOnPartialData && !nonExistingParts.isEmpty())) {
      log.info("Not considering storage candidate:{} as no partitions for fallback range:{}", this, timeRange);
      return false;
    }
    String extraWhere = extraWhereClauseFallback.toString();
    if (!StringUtils.isEmpty(extraWhere)) {
      rangeToExtraWhereFallBack.put(queriedTimeRange, extraWhere);
    }
    return true;
  }

  @Override
  public Set<FactPartition> getParticipatingPartitions() {
    Set<FactPartition> allPartitions = new HashSet<>(numQueriedParts);
    for (Set<FactPartition>  rangePartitions : rangeToPartitions.values()) {
      allPartitions.addAll(rangePartitions);
    }
    return allPartitions;
  }

  private boolean evaluateMeasuresCompleteness(TimeRange timeRange) throws LensException {
    String factDataCompletenessTag = fact.getDataCompletenessTag();
    if (factDataCompletenessTag == null) {
      log.info("Not checking completeness for the fact table:{} as the dataCompletenessTag is not set", fact);
      return true;
    }
    Set<String> measureTag = new HashSet<>();
    Map<String, String> tagToMeasureOrExprMap = new HashMap<>();

    processExpressionsForCompleteness(cubeql, measureTag, tagToMeasureOrExprMap);

    Set<String> measures = cubeql.getQueriedMsrs();
    if (measures == null) {
      measures = new HashSet<>();
    }
    for (String measure : measures) {
      processCubeColForDataCompleteness(cubeql, measure, measure, measureTag, tagToMeasureOrExprMap);
    }
    //Checking if dataCompletenessTag is set for the fact
    if (measureTag.isEmpty()) {
      log.info("No Queried measures with the dataCompletenessTag, hence skipping the availability check");
      return true;
    }
    boolean isDataComplete = false;
    DataCompletenessChecker completenessChecker = client.getCompletenessChecker();
    DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    if (!timeRange.getPartitionColumn().equals(completenessPartCol)) {
      log.info("Completeness check not available for partCol:{}", timeRange.getPartitionColumn());
      return true;
    }
    Date from = timeRange.getFromDate();
    Date to = timeRange.getToDate();
    Map<String, Map<Date, Float>> completenessMap = completenessChecker
      .getCompleteness(factDataCompletenessTag, from, to, measureTag);
    if (completenessMap != null && !completenessMap.isEmpty()) {
      for (Map.Entry<String, Map<Date, Float>> measureCompleteness : completenessMap.entrySet()) {
        String tag = measureCompleteness.getKey();
        for (Map.Entry<Date, Float> completenessResult : measureCompleteness.getValue().entrySet()) {
          if (completenessResult.getValue() < completenessThreshold) {
            log.info("Completeness for the measure_tag {} is {}, threshold: {}, for the hour {}", tag,
              completenessResult.getValue(), completenessThreshold, formatter.format(completenessResult.getKey()));
            String measureorExprFromTag = tagToMeasureOrExprMap.get(tag);
            dataCompletenessMap.computeIfAbsent(measureorExprFromTag, k -> new HashMap<>())
              .put(formatter.format(completenessResult.getKey()), completenessResult.getValue());
            isDataComplete = false;
          }
        }
      }
    }
    return isDataComplete;
  }

  private Set<FactPartition> getPartitions(TimeRange timeRange, TreeSet<UpdatePeriod> updatePeriods,
    boolean addNonExistingParts, boolean failOnPartialData, PartitionRangesForPartitionColumns missingParts)
    throws LensException {
    Set<FactPartition> partitions = new TreeSet<>();
    if (timeRange != null && timeRange.isCoverableBy(updatePeriods)) {
      getPartitions(timeRange.getFromDate(), timeRange.getToDate(), timeRange.getPartitionColumn(),
        partitions, updatePeriods, addNonExistingParts, failOnPartialData, missingParts);
    }
    return partitions;
  }

  @Override
  public boolean isExpressionEvaluable(ExpressionResolver.ExpressionContext expr) {
    return expr.isEvaluable(this);
  }

  /**
   * Update selectAST for StorageCandidate
   * 1. Delete projected select expression if it's not answerable by StorageCandidate.
   * 2. Replace the queried alias with select alias if both are different in a select expr.
   *
   * @param cubeql
   * @throws LensException
   */

  public void updateAnswerableSelectColumns(CubeQueryContext cubeql) throws LensException {
    // update select AST with selected fields
    int currentChild = 0;
    for (int i = 0; i < cubeql.getSelectAST().getChildCount(); i++) {
      ASTNode selectExpr = (ASTNode) queryAst.getSelectAST().getChild(currentChild);
      Set<String> exprCols = HQLParser.getColsInExpr(cubeql.getAliasForTableName(cubeql.getCube()), selectExpr);
      if (getColumns().containsAll(exprCols)) {
        ASTNode aliasNode = HQLParser.findNodeByPath(selectExpr, HiveParser.Identifier);
        String alias = cubeql.getSelectPhrases().get(i).getSelectAlias();
        if (aliasNode != null) {
          String queryAlias = aliasNode.getText();
          if (!queryAlias.equals(alias)) {
            // replace the alias node
            ASTNode newAliasNode = new ASTNode(new CommonToken(HiveParser.Identifier, alias));
            queryAst.getSelectAST().getChild(currentChild)
              .replaceChildren(selectExpr.getChildCount() - 1, selectExpr.getChildCount() - 1, newAliasNode);
          }
        } else {
          // add column alias
          ASTNode newAliasNode = new ASTNode(new CommonToken(HiveParser.Identifier, alias));
          queryAst.getSelectAST().getChild(currentChild).addChild(newAliasNode);
        }
      } else {
        queryAst.getSelectAST().deleteChild(currentChild);
        currentChild--;
      }
      currentChild++;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (super.equals(obj)) {
      return true;
    }

    if (obj == null || !(obj instanceof StorageCandidate)) {
      return false;
    }

    StorageCandidate storageCandidateObj = (StorageCandidate) obj;
    //Assuming that same instance of cube and fact will be used across StorageCandidate s and hence relying directly
    //on == check for these.
    return (this.cube == storageCandidateObj.cube && this.fact == storageCandidateObj.fact && this.storageTable
      .equals(storageCandidateObj.storageTable));
  }

  @Override
  public int hashCode() {
    return this.storageTable.hashCode();
  }

  @Override
  public String toString() {
    return getResolvedName();
  }

  void addValidUpdatePeriod(UpdatePeriod updatePeriod) {
    this.validUpdatePeriods.add(updatePeriod);
  }

  void updateFromString(CubeQueryContext query, Set<Dimension> queryDims,
    Map<Dimension, CandidateDim> dimsToQuery) throws LensException {
    this.dimsToQuery = dimsToQuery;
    String alias = cubeql.getAliasForTableName(cubeql.getCube().getName());
    fromString = getAliasForTable(alias);
    if (query.isAutoJoinResolved()) {
      fromString = query.getAutoJoinCtx().getFromString(fromString, this, queryDims, dimsToQuery, query, cubeql);
    }
  }

  private String getFromTable() throws LensException {
    if (cubeql.isAutoJoinResolved()) {
      return fromString;
    } else {
      return cubeql.getQBFromString(this, getDimsToQuery());
    }
  }

  public String getAliasForTable(String alias) {
    String database = SessionState.get().getCurrentDatabase();
    String ret;
    if (alias == null || alias.isEmpty()) {
      ret = getResolvedName();
    } else {
      ret = getResolvedName() + " " + alias;
    }
    if (StringUtils.isNotBlank(database) && !"default".equalsIgnoreCase(database)) {
      ret = database + "." + ret;
    }
    return ret;
  }

  boolean isUpdatePeriodUseful(UpdatePeriod updatePeriod) {
    return cubeql.getTimeRanges().stream().anyMatch(timeRange -> isUpdatePeriodUseful(timeRange, updatePeriod));
  }

  /**
   * Is the update period useful for this time range. e.g. for a time range of hours and days, monthly
   * and yearly update periods are useless. DAILY and HOURLY are useful. It further checks if the update
   * period answers the range at least partially based on start and end times configured at update period
   * level or at storage or fact level.
   * @param timeRange       The time range
   * @param updatePeriod    Update period
   * @return                Whether it's useless
   */
  private boolean isUpdatePeriodUseful(TimeRange timeRange, UpdatePeriod updatePeriod) {
    try {
      if (!CandidateUtil.isCandidatePartiallyValidForTimeRange(getStorageTableStartDate(updatePeriod),
        getStorageTableEndDate(updatePeriod), timeRange.getFromDate(), timeRange.getToDate())) {
        return false;
      }
      Date storageTblStartDate  = getStorageTableStartDate(updatePeriod);
      Date storageTblEndDate  = getStorageTableEndDate(updatePeriod);
      TimeRange.getBuilder() //TODO date calculation to move to util method and resued
        .fromDate(timeRange.getFromDate().after(storageTblStartDate) ? timeRange.getFromDate() : storageTblStartDate)
        .toDate(timeRange.getToDate().before(storageTblEndDate) ? timeRange.getToDate() : storageTblEndDate)
        .partitionColumn(timeRange.getPartitionColumn())
        .build()
        .truncate(updatePeriod);
      return true;
    } catch (LensException e) {
      return false;
    }
  }

  /**
   * Is time range coverable based on valid update periods of this storage candidate
   *
   * @param timeRange
   * @return
   * @throws LensException
   */
  public boolean isTimeRangeCoverable(TimeRange timeRange) throws LensException {
    return isTimeRangeCoverable(timeRange.getFromDate(), timeRange.getToDate(), validUpdatePeriods);
  }

  /**
   * Is the time range coverable by given update periods.
   * Extracts the max update period, then extracts maximum amount of range from the middle that this update
   * period can cover. Then recurses on the remaining ranges on the left and right side of the extracted chunk
   * using one less update period.
   *
   * @param timeRangeStart
   * @param timeRangeEnd
   * @param intervals   Update periods to check
   * @return          Whether time range is coverable by provided update periods or not.
   */
  private boolean isTimeRangeCoverable(Date timeRangeStart, Date timeRangeEnd,
    Set<UpdatePeriod> intervals) throws LensException {
    if (timeRangeStart.equals(timeRangeEnd) || timeRangeStart.after(timeRangeEnd)) {
      return true;
    }
    if (intervals == null || intervals.isEmpty()) {
      return false;
    }

    UpdatePeriod maxInterval = CubeFactTable.maxIntervalInRange(timeRangeStart, timeRangeEnd, intervals);
    if (maxInterval == null) {
      return false;
    }

    if (maxInterval == UpdatePeriod.CONTINUOUS
      && cubeql.getRangeWriter().getClass().equals(BetweenTimeRangeWriter.class)) {
      return true;
    }

    Date maxIntervalStorageTableStartDate = getStorageTableStartDate(maxInterval);
    Date maxIntervalStorageTableEndDate = getStorageTableEndDate(maxInterval);
    Set<UpdatePeriod> remainingIntervals = Sets.difference(intervals, Sets.newHashSet(maxInterval));

    if (!CandidateUtil.isCandidatePartiallyValidForTimeRange(
      maxIntervalStorageTableStartDate, maxIntervalStorageTableEndDate, timeRangeStart, timeRangeEnd)) {
      //Check the time range in remainingIntervals as maxInterval is not useful
      return isTimeRangeCoverable(timeRangeStart, timeRangeEnd, remainingIntervals);
    }

    Date ceilFromDate = DateUtil.getCeilDate(timeRangeStart.after(maxIntervalStorageTableStartDate)
      ? timeRangeStart : maxIntervalStorageTableStartDate, maxInterval);
    Date floorToDate = DateUtil.getFloorDate(timeRangeEnd.before(maxIntervalStorageTableEndDate)
      ? timeRangeEnd : maxIntervalStorageTableEndDate, maxInterval);
    if (ceilFromDate.equals(floorToDate) || floorToDate.before(ceilFromDate)) {
      return isTimeRangeCoverable(timeRangeStart, timeRangeEnd, remainingIntervals);
    }

    //ceilFromDate to floorToDate time range is covered by maxInterval (though there may be holes.. but that's ok)
    //Check the remaining part of time range in remainingIntervals
    return isTimeRangeCoverable(timeRangeStart, ceilFromDate, remainingIntervals)
      && isTimeRangeCoverable(floorToDate, timeRangeEnd, remainingIntervals);
  }

  private Date getStorageTableStartDate(UpdatePeriod interval) throws LensException {
    if (!isStorageTblsAtUpdatePeriodLevel) {
      //In this case the start time and end time is at Storage Level and will be same for all update periods.
      return this.startTime;
    }
    return client.getStorageTableStartDate(
      client.getStorageTableName(fact.getName(), storageName, interval), fact.getName());
  }

  private Date getStorageTableEndDate(UpdatePeriod interval) throws LensException {
    if (!isStorageTblsAtUpdatePeriodLevel) {
      //In this case the start time and end time is at Storage Level and will be same for all update periods.
      return this.endTime;
    }
    return client.getStorageTableEndDate(
      client.getStorageTableName(fact.getName(), storageName, interval), fact.getName());
  }


  public String getResolvedName() {
    if (resolvedName == null) {
      return storageTable;
    }
    return resolvedName;
  }

  /**
   * Splits the Storage Candidates into multiple Storage Candidates if storage candidate has multiple
   * storage tables (one per update period)
   *
   * @return
   * @throws LensException
   */
  public Collection<StorageCandidate> splitAtUpdatePeriodLevelIfReq() throws LensException {
    if (!isStorageTblsAtUpdatePeriodLevel) {
      return Lists.newArrayList(this); // No need to explode in this case
    }
    return getPeriodSpecificStorageCandidates();
  }

  private Collection<StorageCandidate> getPeriodSpecificStorageCandidates() throws LensException {
    List<StorageCandidate> periodSpecificScList = new ArrayList<>(participatingUpdatePeriods.size());
    StorageCandidate updatePeriodSpecificSc;
    for (UpdatePeriod period : participatingUpdatePeriods) {
      updatePeriodSpecificSc = new StorageCandidate(this);
      updatePeriodSpecificSc.truncatePartitions(period);
      updatePeriodSpecificSc.setResolvedName(client.getStorageTableName(fact.getName(),
          storageName, period));
      periodSpecificScList.add(updatePeriodSpecificSc);
    }
    return periodSpecificScList;
  }

  /**
   * Truncates partitions in {@link #rangeToPartitions} such that only partitions belonging to
   * the passed undatePeriod are retained.
   * @param updatePeriod
   */
  private void truncatePartitions(UpdatePeriod updatePeriod) {
    Iterator<Map.Entry<TimeRange, Set<FactPartition>>> rangeItr = rangeToPartitions.entrySet().iterator();
    while (rangeItr.hasNext()) {
      Map.Entry<TimeRange, Set<FactPartition>> rangeEntry = rangeItr.next();
      Iterator<FactPartition> partitionItr = rangeEntry.getValue().iterator();
      while (partitionItr.hasNext()) {
        if (!partitionItr.next().getPeriod().equals(updatePeriod)) {
          partitionItr.remove();
        }
      }
      if (rangeEntry.getValue().isEmpty()) {
        rangeItr.remove();
      }
    }
  }


}
