package io.cassandrareaper.storage.cassandra;

import io.cassandrareaper.AppContext;
import io.cassandrareaper.core.RepairSegment;
import io.cassandrareaper.core.Segment;
import io.cassandrareaper.service.RingRange;
import io.cassandrareaper.storage.JsonParseUtils;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.joda.time.DateTime;

public class RepairSegmentDAO {
  private final CassandraStorage cassandraStorage;

  private final Session session;
  public PreparedStatement insertRepairSegmentPrepStmt;
  public PreparedStatement insertRepairSegmentIncrementalPrepStmt;
  PreparedStatement updateRepairSegmentPrepStmt;
  PreparedStatement insertRepairSegmentEndTimePrepStmt;
  PreparedStatement getRepairSegmentPrepStmt;
  PreparedStatement getRepairSegmentsByRunIdPrepStmt;
  PreparedStatement getRepairSegmentCountByRunIdPrepStmt;
  @Nullable // null on Cassandra-2 as it's not supported syntax
  PreparedStatement getRepairSegmentsByRunIdAndStatePrepStmt = null;
  @Nullable // null on Cassandra-2 as it's not supported syntax
  PreparedStatement getRepairSegmentCountByRunIdAndStatePrepStmt = null;

  public RepairSegmentDAO(CassandraStorage cassandraStorage, Session session) {
    this.cassandraStorage = cassandraStorage;
    this.session = session;
  }

  
  public int getSegmentAmountForRepairRun(UUID runId) {
    return (int) session
        .execute(getRepairSegmentCountByRunIdAndStatePrepStmt.bind(runId))
        .one()
        .getLong(0);
  }


  public int getSegmentAmountForRepairRunWithState(UUID runId, RepairSegment.State state) {
    if (null != getRepairSegmentCountByRunIdAndStatePrepStmt) {
      return (int) session
          .execute(getRepairSegmentCountByRunIdAndStatePrepStmt.bind(runId, state.ordinal()))
          .one()
          .getLong(0);
    } else {
      // legacy mode for Cassandra-2 backends
      return cassandraStorage.getSegmentsWithState(runId, state).size();
    }
  }

  public boolean updateRepairSegment(RepairSegment segment) {

    assert cassandraStorage.hasLeadOnSegment(segment)
        || (cassandraStorage.hasLeadOnSegment(segment.getRunId())
        && cassandraStorage.getRepairUnit(segment.getRepairUnitId()).getIncrementalRepair())
        : "non-leader trying to update repair segment " + segment.getId() + " of run " + segment.getRunId();

    return cassandraStorage.updateRepairSegmentUnsafe(segment);
  }


  public boolean updateRepairSegmentUnsafe(RepairSegment segment) {

    BatchStatement updateRepairSegmentBatch = new BatchStatement(BatchStatement.Type.UNLOGGED);

    updateRepairSegmentBatch.add(
        updateRepairSegmentPrepStmt.bind(
            segment.getRunId(),
            segment.getId(),
            segment.getState().ordinal(),
            segment.getCoordinatorHost(),
            segment.hasStartTime() ? segment.getStartTime().toDate() : null,
            segment.getFailCount(),
            segment.getHostID()
        )
    );

    if (null != segment.getEndTime() || RepairSegment.State.NOT_STARTED == segment.getState()) {

      Preconditions.checkArgument(
          RepairSegment.State.RUNNING != segment.getState(),
          "un/setting endTime not permitted when state is RUNNING");

      Preconditions.checkArgument(
          RepairSegment.State.NOT_STARTED != segment.getState() || !segment.hasEndTime(),
          "endTime can only be nulled when state is NOT_STARTED");

      Preconditions.checkArgument(
          RepairSegment.State.DONE != segment.getState() || segment.hasEndTime(),
          "endTime can't be null when state is DONE");

      updateRepairSegmentBatch.add(
          insertRepairSegmentEndTimePrepStmt.bind(
              segment.getRunId(),
              segment.getId(),
              segment.hasEndTime() ? segment.getEndTime().toDate() : null));
    } else if (RepairSegment.State.STARTED == segment.getState()) {
      updateRepairSegmentBatch.setConsistencyLevel(ConsistencyLevel.EACH_QUORUM);
    }
    session.execute(updateRepairSegmentBatch);
    return true;
  }


  public Optional<RepairSegment> getRepairSegment(UUID runId, UUID segmentId) {
    RepairSegment segment = null;
    Row segmentRow = session.execute(getRepairSegmentPrepStmt.bind(runId, segmentId)).one();
    if (segmentRow != null) {
      segment = createRepairSegmentFromRow(segmentRow);
    }

    return Optional.ofNullable(segment);
  }


  public Collection<RepairSegment> getRepairSegmentsForRun(UUID runId) {
    Collection<RepairSegment> segments = Lists.newArrayList();
    // First gather segments ids
    ResultSet segmentsIdResultSet = session.execute(getRepairSegmentsByRunIdPrepStmt.bind(runId));
    for (Row segmentRow : segmentsIdResultSet) {
      segments.add(createRepairSegmentFromRow(segmentRow));
    }

    return segments;
  }

  static boolean segmentIsWithinRange(RepairSegment segment, RingRange range) {
    return range.encloses(new RingRange(segment.getStartToken(), segment.getEndToken()));
  }

  static RepairSegment createRepairSegmentFromRow(Row segmentRow) {

    List<RingRange> tokenRanges
        = JsonParseUtils.parseRingRangeList(Optional.ofNullable(segmentRow.getString("token_ranges")));

    Segment.Builder segmentBuilder = Segment.builder();

    if (tokenRanges.size() > 0) {
      segmentBuilder.withTokenRanges(tokenRanges);
      if (null != segmentRow.getMap("replicas", String.class, String.class)) {
        segmentBuilder = segmentBuilder.withReplicas(segmentRow.getMap("replicas", String.class, String.class));
      }
    } else {
      // legacy path, for segments that don't have a token range list
      segmentBuilder.withTokenRange(
          new RingRange(
              new BigInteger(segmentRow.getVarint("start_token") + ""),
              new BigInteger(segmentRow.getVarint("end_token") + "")));
    }

    RepairSegment.Builder builder
        = RepairSegment.builder(segmentBuilder.build(), segmentRow.getUUID("repair_unit_id"))
        .withRunId(segmentRow.getUUID("id"))
        .withState(RepairSegment.State.values()[segmentRow.getInt("segment_state")])
        .withFailCount(segmentRow.getInt("fail_count"));

    if (null != segmentRow.getString("coordinator_host")) {
      builder = builder.withCoordinatorHost(segmentRow.getString("coordinator_host"));
    }
    if (null != segmentRow.getTimestamp("segment_start_time")) {
      builder = builder.withStartTime(new DateTime(segmentRow.getTimestamp("segment_start_time")));
    }
    if (null != segmentRow.getTimestamp("segment_end_time")) {
      builder = builder.withEndTime(new DateTime(segmentRow.getTimestamp("segment_end_time")));
    }
    if (null != segmentRow.getMap("replicas", String.class, String.class)) {
      builder = builder.withReplicas(segmentRow.getMap("replicas", String.class, String.class));
    }

    if (null != segmentRow.getUUID("host_id")) {
      builder = builder.withHostID(segmentRow.getUUID("host_id"));
    }

    return builder.withId(segmentRow.getUUID("segment_id")).build();
  }


  public List<RepairSegment> getNextFreeSegments(UUID runId) {
    List<RepairSegment> segments = Lists.<RepairSegment>newArrayList(cassandraStorage.getRepairSegmentsForRun(runId));
    Collections.shuffle(segments);

    Set<String> lockedNodes = cassandraStorage.getLockedNodesForRun(runId);
    List<RepairSegment> candidates = segments.stream()
        .filter(seg -> segmentIsCandidate(seg, lockedNodes))
        .collect(Collectors.toList());
    return candidates;
  }


  public List<RepairSegment> getNextFreeSegmentsForRanges(
      UUID runId,
      List<RingRange> ranges) {
    List<RepairSegment> segments
        = Lists.<RepairSegment>newArrayList(cassandraStorage.getRepairSegmentsForRun(runId));
    Collections.shuffle(segments);
    Set<String> lockedNodes = cassandraStorage.getLockedNodesForRun(runId);
    List<RepairSegment> candidates = segments.stream()
        .filter(seg -> segmentIsCandidate(seg, lockedNodes))
        .filter(seg -> segmentIsWithinRanges(seg, ranges))
        .collect(Collectors.toList());

    return candidates;
  }

  boolean segmentIsWithinRanges(RepairSegment seg, List<RingRange> ranges) {
    for (RingRange range : ranges) {
      if (segmentIsWithinRange(seg, range)) {
        return true;
      }
    }

    return false;
  }

  boolean segmentIsCandidate(RepairSegment seg, Set<String> lockedNodes) {
    return seg.getState().equals(RepairSegment.State.NOT_STARTED)
        && Sets.intersection(lockedNodes, seg.getReplicas().keySet()).isEmpty();
  }


  public Collection<RepairSegment> getSegmentsWithState(UUID runId, RepairSegment.State segmentState) {
    Collection<RepairSegment> segments = Lists.newArrayList();

    Statement statement = null != getRepairSegmentsByRunIdAndStatePrepStmt
        ? getRepairSegmentsByRunIdAndStatePrepStmt.bind(runId, segmentState.ordinal())
        // legacy mode for Cassandra-2 backends
        : getRepairSegmentsByRunIdPrepStmt.bind(runId);

    if (RepairSegment.State.STARTED == segmentState) {
      statement = statement.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM);
    }
    for (Row segmentRow : session.execute(statement)) {
      if (segmentRow.getInt("segment_state") == segmentState.ordinal()) {
        segments.add(createRepairSegmentFromRow(segmentRow));
      }
    }
    return segments;
  }
}