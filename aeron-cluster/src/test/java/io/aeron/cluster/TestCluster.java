/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.CommonContext;
import io.aeron.Counter;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.BitUtil;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.status.AtomicCounter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.aeron.Aeron.NULL_VALUE;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestCluster implements AutoCloseable
{
    private static final int SEGMENT_FILE_LENGTH = 16 * 1024 * 1024;
    private static final long MAX_CATALOG_ENTRIES = 128;
    private static final String LOG_CHANNEL =
        "aeron:udp?term-length=256k|control-mode=manual|control=localhost:20550";
    private static final String ARCHIVE_CONTROL_REQUEST_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8010";
    private static final String ARCHIVE_CONTROL_RESPONSE_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8020";
    private static final String ARCHIVE_RECORDING_EVENTS_CHANNEL =
        "aeron:udp?control-mode=dynamic|control=localhost:8030";

    private final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
    private final MutableInteger responseCount = new MutableInteger();
    private final MutableInteger newLeaderEvent = new MutableInteger();
    private final EgressListener egressMessageListener = new EgressListener()
    {
        public void onMessage(
            final long clusterSessionId,
            final long timestampMs,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header)
        {
            responseCount.value++;
        }

        public void newLeader(
            final long clusterSessionId,
            final long leadershipTermId,
            final int leaderMemberId,
            final String memberEndpoints)
        {
            newLeaderEvent.value++;
        }
    };

    private final TestNode[] nodes;
    private final String staticClusterMembers;
    private final String staticClusterMemberEndpoints;
    private final String[] clusterMembersEndpoints;
    private final String clusterMembersStatusEndpoints;
    private final int staticMemberCount;
    private final int dynamicMemberCount;
    private final int appointedLeaderId;

    private MediaDriver clientMediaDriver;
    private AeronCluster client;

    TestCluster(final int staticMemberCount, final int dynamicMemberCount, final int appointedLeaderId)
    {
        if (staticMemberCount >= 10)
        {
            throw new IllegalArgumentException(
                "too many members memberCount=" + staticMemberCount + ": only support 9");
        }

        this.nodes = new TestNode[staticMemberCount + dynamicMemberCount];
        this.staticClusterMembers = clusterMembersString(staticMemberCount);
        this.staticClusterMemberEndpoints = clientMemberEndpoints(staticMemberCount);
        this.clusterMembersEndpoints = clusterMembersEndpoints(staticMemberCount + dynamicMemberCount);
        this.clusterMembersStatusEndpoints = clusterMembersStatusEndpoints(staticMemberCount);
        this.staticMemberCount = staticMemberCount;
        this.dynamicMemberCount = dynamicMemberCount;
        this.appointedLeaderId = appointedLeaderId;
    }

    public void close()
    {
        CloseHelper.close(client);
        CloseHelper.close(clientMediaDriver);

        if (null != clientMediaDriver)
        {
            clientMediaDriver.context().deleteAeronDirectory();
        }

        for (int i = 0, length = nodes.length; i < length; i++)
        {
            if (null != nodes[i])
            {
                nodes[i].close();
                nodes[i].cleanUp();
            }
        }
    }

    static TestCluster startThreeNodeStaticCluster(final int appointedLeaderId)
    {
        final TestCluster testCluster = new TestCluster(3, 0, appointedLeaderId);
        for (int i = 0; i < 3; i++)
        {
            testCluster.startStaticNode(i, true);
        }

        return testCluster;
    }

    static TestCluster startThreeNodeStaticCluster(
        final int appointedLeaderId, final Supplier<? extends TestNode.TestService> serviceSupplier)
    {
        final TestCluster testCluster = new TestCluster(3, 0, appointedLeaderId);
        for (int i = 0; i < 3; i++)
        {
            testCluster.startStaticNode(i, true, serviceSupplier);
        }

        return testCluster;
    }

    static TestCluster startSingleNodeStaticCluster()
    {
        final TestCluster testCluster = new TestCluster(1, 0, 0);
        testCluster.startStaticNode(0, true);

        return testCluster;
    }

    static TestCluster startCluster(final int staticMemberCount, final int dynamicMemberCount)
    {
        final TestCluster testCluster = new TestCluster(staticMemberCount, dynamicMemberCount, NULL_VALUE);
        for (int i = 0; i < staticMemberCount; i++)
        {
            testCluster.startStaticNode(i, true);
        }

        return testCluster;
    }

    TestNode startStaticNode(final int index, final boolean cleanStart)
    {
        return startStaticNode(index, cleanStart, TestNode.TestService::new);
    }

    TestNode startStaticNode(
        final int index, final boolean cleanStart, final Supplier<? extends TestNode.TestService> serviceSupplier)
    {
        final String baseDirName = CommonContext.getAeronDirectoryName() + "-" + index;
        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + index + "-driver";
        final TestNode.Context context = new TestNode.Context(serviceSupplier.get().index(index));

        context.aeronArchiveContext
            .controlRequestChannel(memberSpecificPort(ARCHIVE_CONTROL_REQUEST_CHANNEL, index))
            .controlRequestStreamId(100)
            .controlResponseChannel(memberSpecificPort(ARCHIVE_CONTROL_RESPONSE_CHANNEL, index))
            .controlResponseStreamId(110 + index)
            .recordingEventsChannel(memberSpecificPort(ARCHIVE_RECORDING_EVENTS_CHANNEL, index))
            .aeronDirectoryName(baseDirName);

        context.mediaDriverContext
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .errorHandler(TestUtil.errorHandler(index))
            .dirDeleteOnStart(true);

        context.archiveContext
            .maxCatalogEntries(MAX_CATALOG_ENTRIES)
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(baseDirName, "archive"))
            .controlChannel(context.aeronArchiveContext.controlRequestChannel())
            .controlStreamId(context.aeronArchiveContext.controlRequestStreamId())
            .localControlChannel("aeron:ipc?term-length=64k")
            .localControlStreamId(context.aeronArchiveContext.controlRequestStreamId())
            .recordingEventsChannel(context.aeronArchiveContext.recordingEventsChannel())
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(cleanStart);

        context.consensusModuleContext
            .errorHandler(TestUtil.errorHandler(index))
            .clusterMemberId(index)
            .clusterMembers(staticClusterMembers)
            .appointedLeaderId(appointedLeaderId)
            .aeronDirectoryName(aeronDirName)
            .clusterDir(new File(baseDirName, "consensus-module"))
            .ingressChannel("aeron:udp?term-length=64k")
            .logChannel(memberSpecificPort(LOG_CHANNEL, index))
            .archiveContext(context.aeronArchiveContext.clone())
            .deleteDirOnStart(cleanStart);

        context.serviceContainerContext
            .aeronDirectoryName(aeronDirName)
            .archiveContext(context.aeronArchiveContext.clone())
            .clusterDir(new File(baseDirName, "service"))
            .clusteredService(context.service)
            .errorHandler(TestUtil.errorHandler(index));

        nodes[index] = new TestNode(context);

        return nodes[index];
    }

    TestNode startDynamicNode(final int index, final boolean cleanStart)
    {
        return startDynamicNode(index, cleanStart, TestNode.TestService::new);
    }

    TestNode startDynamicNode(
        final int index, final boolean cleanStart, final Supplier<? extends TestNode.TestService> serviceSupplier)
    {
        final String baseDirName = CommonContext.getAeronDirectoryName() + "-" + index;
        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + index + "-driver";
        final TestNode.Context context = new TestNode.Context(serviceSupplier.get().index(index));

        context.aeronArchiveContext
            .controlRequestChannel(memberSpecificPort(ARCHIVE_CONTROL_REQUEST_CHANNEL, index))
            .controlRequestStreamId(100)
            .controlResponseChannel(memberSpecificPort(ARCHIVE_CONTROL_RESPONSE_CHANNEL, index))
            .controlResponseStreamId(110 + index)
            .recordingEventsChannel(memberSpecificPort(ARCHIVE_RECORDING_EVENTS_CHANNEL, index))
            .aeronDirectoryName(baseDirName);

        context.mediaDriverContext
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
            .errorHandler(TestUtil.errorHandler(index))
            .dirDeleteOnStart(true);

        context.archiveContext
            .maxCatalogEntries(MAX_CATALOG_ENTRIES)
            .segmentFileLength(SEGMENT_FILE_LENGTH)
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(baseDirName, "archive"))
            .controlChannel(context.aeronArchiveContext.controlRequestChannel())
            .controlStreamId(context.aeronArchiveContext.controlRequestStreamId())
            .localControlChannel("aeron:ipc?term-length=64k")
            .localControlStreamId(context.aeronArchiveContext.controlRequestStreamId())
            .recordingEventsChannel(context.aeronArchiveContext.recordingEventsChannel())
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(cleanStart);

        context.consensusModuleContext
            .errorHandler(TestUtil.errorHandler(index))
            .clusterMemberId(NULL_VALUE)
            .clusterMembers("")
            .clusterMembersStatusEndpoints(clusterMembersStatusEndpoints)
            .memberEndpoints(clusterMembersEndpoints[index])
            .aeronDirectoryName(aeronDirName)
            .clusterDir(new File(baseDirName, "consensus-module"))
            .ingressChannel("aeron:udp?term-length=64k")
            .logChannel(memberSpecificPort(LOG_CHANNEL, index))
            .archiveContext(context.aeronArchiveContext.clone())
            .deleteDirOnStart(cleanStart);

        context.serviceContainerContext
            .aeronDirectoryName(aeronDirName)
            .archiveContext(context.aeronArchiveContext.clone())
            .clusterDir(new File(baseDirName, "service"))
            .clusteredService(context.service)
            .errorHandler(TestUtil.errorHandler(index));

        nodes[index] = new TestNode(context);

        return nodes[index];
    }

    void stopNode(final TestNode testNode)
    {
        testNode.close();
    }

    void stopAllNodes()
    {
        for (int i = 0, length = nodes.length; i < length; i++)
        {
            nodes[i].close();
        }
    }

    void restartAllNodes(final boolean cleanStart)
    {
        for (int i = 0, length = nodes.length; i < length; i++)
        {
            startStaticNode(i, cleanStart);
        }
    }

    String staticClusterMembers()
    {
        return staticClusterMembers;
    }

    AeronCluster client()
    {
        return client;
    }

    ExpandableArrayBuffer msgBuffer()
    {
        return msgBuffer;
    }

    void reconnectClient()
    {
        final String aeronDirName = CommonContext.getAeronDirectoryName();
        client.close();
        client = AeronCluster.connect(
            new AeronCluster.Context()
                .egressListener(egressMessageListener)
                .aeronDirectoryName(aeronDirName)
                .ingressChannel("aeron:udp")
                .clusterMemberEndpoints(staticClusterMemberEndpoints));
    }

    void connectClient()
    {
        final String aeronDirName = CommonContext.getAeronDirectoryName();

        clientMediaDriver = MediaDriver.launch(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .aeronDirectoryName(aeronDirName));

        client = AeronCluster.connect(
            new AeronCluster.Context()
                .egressListener(egressMessageListener)
                .aeronDirectoryName(aeronDirName)
                .ingressChannel("aeron:udp")
                .clusterMemberEndpoints(staticClusterMemberEndpoints));
    }

    void sendMessages(final int messageCount)
    {
        for (int i = 0; i < messageCount; i++)
        {
            msgBuffer.putInt(0, i);
            sendMessage(BitUtil.SIZE_OF_INT);
        }
    }

    void sendMessage(final int messageLength)
    {
        while (client.offer(msgBuffer, 0, messageLength) < 0)
        {
            TestUtil.checkInterruptedStatus();
            client.pollEgress();
            Thread.yield();
        }

        client.pollEgress();
    }

    void awaitResponses(final int messageCount)
    {
        final EpochClock epochClock = client.context().aeron().context().epochClock();
        long deadlineMs = epochClock.time() + TimeUnit.SECONDS.toMillis(1);

        while (responseCount.get() < messageCount)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
            client.pollEgress();

            final long nowMs = epochClock.time();
            if (nowMs > deadlineMs)
            {
                client.sendKeepAlive();
                deadlineMs = nowMs + TimeUnit.SECONDS.toMillis(1);
            }
        }
    }

    void awaitLeadershipEvent(final int count)
    {
        while (newLeaderEvent.get() < count)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
            client.pollEgress();
        }
    }

    TestNode findLeader(final int skipIndex)
    {
        for (int i = 0; i < staticMemberCount; i++)
        {
            final TestNode node = nodes[i];
            if (i == skipIndex || null == node || node.isClosed())
            {
                continue;
            }

            if (node.isLeader() && null == node.electionState())
            {
                return node;
            }
        }

        return null;
    }

    TestNode findLeader()
    {
        return findLeader(NULL_VALUE);
    }

    TestNode awaitLeader(final int skipIndex) throws InterruptedException
    {
        TestNode leaderNode;
        while (null == (leaderNode = findLeader(skipIndex)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        return leaderNode;
    }

    TestNode awaitLeader() throws InterruptedException
    {
        return awaitLeader(NULL_VALUE);
    }

    List<TestNode> followers()
    {
        final ArrayList<TestNode> followers = new ArrayList<>();

        for (int i = 0, length = nodes.length; i < length; i++)
        {
            if (!nodes[i].isClosed() && nodes[i].isFollower())
            {
                followers.add(nodes[i]);
            }
        }

        return followers;
    }

    TestNode node(final int index)
    {
        return nodes[index];
    }

    void takeSnapshot(final TestNode leaderNode)
    {
        final AtomicCounter controlToggle = ClusterControl.findControlToggle(leaderNode.countersReader());
        assertNotNull(controlToggle);
        assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));
    }

    void shutdownCluster(final TestNode leaderNode)
    {
        final AtomicCounter controlToggle = ClusterControl.findControlToggle(leaderNode.countersReader());
        assertNotNull(controlToggle);
        assertTrue(ClusterControl.ToggleState.SHUTDOWN.toggle(controlToggle));
    }

    void abortCluster(final TestNode leaderNode)
    {
        final AtomicCounter controlToggle = ClusterControl.findControlToggle(leaderNode.countersReader());
        assertNotNull(controlToggle);
        assertTrue(ClusterControl.ToggleState.ABORT.toggle(controlToggle));
    }

    void awaitSnapshotCounter(final TestNode node, final long value)
    {
        final Counter snapshotCounter = node.consensusModule().context().snapshotCounter();
        while (snapshotCounter.get() != value)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }
    }

    void awaitNodeTermination(final TestNode node)
    {
        while (!node.hasMemberTerminated() || !node.hasServiceTerminated())
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }
    }

    void awaitMessageCountForService(final TestNode node, final int messageCount)
    {
        final EpochClock epochClock = client.context().aeron().context().epochClock();
        long deadlineMs = epochClock.time() + TimeUnit.SECONDS.toMillis(1);

        while (node.service().messageCount() < messageCount)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();

            final long nowMs = epochClock.time();
            if (nowMs > deadlineMs)
            {
                client.sendKeepAlive();
                deadlineMs = nowMs + TimeUnit.SECONDS.toMillis(1);
            }
        }
    }

    void awaitSnapshotLoadedForService(final TestNode node)
    {
        while (!node.service().wasSnapshotLoaded())
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }
    }

    void awaitNeutralControlToggle(final TestNode leaderNode)
    {
        final AtomicCounter controlToggle = ClusterControl.findControlToggle(leaderNode.countersReader());
        assertNotNull(controlToggle);
        while (controlToggle.get() != ClusterControl.ToggleState.NEUTRAL.code())
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }
    }

    private static String memberSpecificPort(final String channel, final int memberId)
    {
        return channel.substring(0, channel.length() - 1) + memberId;
    }

    private static String clusterMembersString(final int memberCount)
    {
        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < memberCount; i++)
        {
            builder
                .append(i).append(',')
                .append("localhost:2011").append(i).append(',')
                .append("localhost:2022").append(i).append(',')
                .append("localhost:2033").append(i).append(',')
                .append("localhost:2044").append(i).append(',')
                .append("localhost:801").append(i).append('|');
        }

        builder.setLength(builder.length() - 1);

        return builder.toString();
    }

    private static String clientMemberEndpoints(final int memberCount)
    {
        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < memberCount; i++)
        {
            builder
                .append(i).append('=')
                .append("localhost:2011").append(i).append(',');
        }

        builder.setLength(builder.length() - 1);

        return builder.toString();
    }

    private static String[] clusterMembersEndpoints(final int maxMemberCount)
    {
        final String[] clusterMembersEndpoints = new String[maxMemberCount];

        for (int i = 0; i < maxMemberCount; i++)
        {
            clusterMembersEndpoints[i] = "localhost:2011" + i + ',' +
                "localhost:2022" + i + ',' +
                "localhost:2033" + i + ',' +
                "localhost:2044" + i + ',' +
                "localhost:801" + i;
        }

        return clusterMembersEndpoints;
    }

    private static String clusterMembersStatusEndpoints(final int staticMemberCount)
    {
        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < staticMemberCount; i++)
        {
            builder.append("localhost:2022").append(i).append(',');
        }

        builder.setLength(builder.length() - 1);

        return builder.toString();
    }
}
