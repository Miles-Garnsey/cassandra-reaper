package com.spotify.reaper.unit.resources;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.spotify.reaper.AppContext;
import com.spotify.reaper.ReaperException;
import com.spotify.reaper.cassandra.JmxConnectionFactory;
import com.spotify.reaper.cassandra.JmxProxy;
import com.spotify.reaper.cassandra.RepairStatusHandler;
import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.resources.ClusterResource;
import com.spotify.reaper.storage.MemoryStorage;
import com.spotify.reaper.unit.service.TestRepairConfiguration;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.hibernate.validator.internal.util.Contracts.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClusterResourceTest {

  static final String CLUSTER_NAME = "testcluster";
  static final String PARTITIONER = "org.apache.cassandra.dht.RandomPartitioner";
  static final String SEED_HOST = "TestHost";
  static final URI SAMPLE_URI = URI.create("http://test");
  static final String I_DO_EXIST = "i_do_exist";
  static final String I_DONT_EXIST = "i_dont_exist";

  @Before
  public void setUp() throws Exception {

  }

  @Test
  public void testAddCluster() throws Exception {
    final MockObjects mocks = initMocks();

    ClusterResource clusterResource = new ClusterResource(mocks.context);
    Response response = clusterResource.addCluster(mocks.uriInfo, Optional.of(SEED_HOST));

    assertEquals(201, response.getStatus());
    assertEquals(1, mocks.context.storage.getClusters().size());

    Cluster cluster = mocks.context.storage.getCluster(CLUSTER_NAME).get();
    assertNotNull(cluster, "Did not find expected cluster");
    assertEquals(0, mocks.context.storage.getRepairRunsForCluster(cluster.getName()).size());
    assertEquals(CLUSTER_NAME, cluster.getName());
    assertEquals(1, cluster.getSeedHosts().size());
    assertEquals(SEED_HOST, cluster.getSeedHosts().iterator().next());
  }

  @Test
  public void testAddExistingCluster() throws Exception {
    final MockObjects mocks = initMocks();
    when(mocks.jmxProxy.getLiveNodes()).thenReturn(Arrays.asList(SEED_HOST));

    Cluster cluster = new Cluster(CLUSTER_NAME, PARTITIONER, Sets.newHashSet(SEED_HOST));
    mocks.context.storage.addCluster(cluster);

    ClusterResource clusterResource = new ClusterResource(mocks.context);
    Response response = clusterResource.addCluster(mocks.uriInfo, Optional.of(SEED_HOST));
    assertEquals(403, response.getStatus());
    assertTrue(response.getEntity() instanceof String);
    String msg = response.getEntity().toString();
    assertTrue(msg.contains("already exists"));
    assertEquals(1, mocks.context.storage.getClusters().size());
  }

  @Test
  public void testGetNonExistingCluster() throws ReaperException {
    final MockObjects mocks = initMocks();
    when(mocks.jmxProxy.getLiveNodes()).thenReturn(Arrays.asList(SEED_HOST));

    ClusterResource clusterResource = new ClusterResource(mocks.context);
    Response response = clusterResource.getCluster(I_DONT_EXIST, Optional.<Integer>absent());
    assertEquals(404, response.getStatus());
  }

  @Test
  public void testGetExistingCluster() throws ReaperException {
    final MockObjects mocks = initMocks();
    when(mocks.jmxProxy.getLiveNodes()).thenReturn(Arrays.asList(SEED_HOST));

    Cluster cluster = new Cluster(I_DO_EXIST, PARTITIONER, Sets.newHashSet(SEED_HOST));
    mocks.context.storage.addCluster(cluster);

    ClusterResource clusterResource = new ClusterResource(mocks.context);
    Response response = clusterResource.getCluster(I_DO_EXIST, Optional.<Integer>absent());
    assertEquals(200, response.getStatus());
  }

  @Test
  public void testModifyClusterSeeds() throws ReaperException {
    final MockObjects mocks = initMocks();

    ClusterResource clusterResource = new ClusterResource(mocks.context);
    clusterResource.addCluster(mocks.uriInfo, Optional.of(SEED_HOST));
    doReturn(Arrays.asList(SEED_HOST + 1)).when(mocks.jmxProxy).getLiveNodes();

    Response response =
        clusterResource.modifyClusterSeed(mocks.uriInfo, CLUSTER_NAME, Optional.of(SEED_HOST + 1));

    assertEquals(200, response.getStatus());
    assertEquals(1, mocks.context.storage.getClusters().size());

    Cluster cluster = mocks.context.storage.getCluster(CLUSTER_NAME).get();
    assertEquals(1, cluster.getSeedHosts().size());
    assertEquals(SEED_HOST + 1, cluster.getSeedHosts().iterator().next());


    response =
        clusterResource.modifyClusterSeed(mocks.uriInfo, CLUSTER_NAME, Optional.of(SEED_HOST + 1));
    assertEquals(304, response.getStatus());
    //when(mocks.jmxProxy.getLiveNodes()).thenReturn(Arrays.asList(SEED_HOST));
  }

  @Test
  public void addingAClusterAutomaticallySetupSchedulingRepairsWhenEnabled() throws Exception {
    final MockObjects mocks = initMocks();
    when(mocks.jmxProxy.getLiveNodes()).thenReturn(Arrays.asList(SEED_HOST));

    when(mocks.jmxProxy.getKeyspaces()).thenReturn(Lists.newArrayList("keyspace1"));
    when(mocks.jmxProxy.getTableNamesForKeyspace("keyspace1"))
        .thenReturn(Sets.newHashSet("table1"));

    mocks.context.config =
        TestRepairConfiguration.defaultConfigBuilder()
            .withAutoScheduling(
                TestRepairConfiguration.defaultAutoSchedulingConfigBuilder()
                    .thatIsEnabled()
                    .withTimeBeforeFirstSchedule(Duration.ofMinutes(1))
                    .build())
            .build();

    ClusterResource clusterResource = new ClusterResource(mocks.context);
    Response response = clusterResource.addCluster(mocks.uriInfo, Optional.of(SEED_HOST));

    assertEquals(201, response.getStatus());
    assertThat(mocks.context.storage.getAllRepairSchedules()).hasSize(1);
    assertThat(
            mocks.context.storage.getRepairSchedulesForClusterAndKeyspace(
                CLUSTER_NAME, "keyspace1"))
        .hasSize(1);
  }

  private MockObjects initMocks() throws ReaperException {
    AppContext context = new AppContext();
    UriInfo uriInfo;
    JmxProxy jmxProxy;

    context.storage = new MemoryStorage();
    context.config = TestRepairConfiguration.defaultConfig();

    uriInfo = mock(UriInfo.class);
    when(uriInfo.getAbsolutePath()).thenReturn(SAMPLE_URI);
    when(uriInfo.getBaseUri()).thenReturn(SAMPLE_URI);

    jmxProxy = mock(JmxProxy.class);
    when(jmxProxy.getClusterName()).thenReturn(CLUSTER_NAME);
    when(jmxProxy.getPartitioner()).thenReturn(PARTITIONER);

    context.jmxConnectionFactory = new JmxConnectionFactory() {
      @Override
      public JmxProxy connect(Optional<RepairStatusHandler> handler, String host, int connectionTimeout)
          throws ReaperException {
        return jmxProxy;
      }
    };

    return new MockObjects(context, uriInfo, jmxProxy);

  }

  private static final class MockObjects {
    public final AppContext context;
    public final UriInfo uriInfo;
    public final JmxProxy jmxProxy;

    public MockObjects(AppContext context, UriInfo uriInfo, JmxProxy jmxProxy) {
      super();
      this.context = context;
      this.uriInfo = uriInfo;
      this.jmxProxy = jmxProxy;
    }



  }

}
