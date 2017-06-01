/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.actions.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.balancer.TestBalancer;
import org.apache.hadoop.util.StringUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.smartdata.SmartContext;
import org.smartdata.actions.ActionStatus;
import org.smartdata.actions.hdfs.move.MoverStatus;
import org.smartdata.conf.SmartConf;

/**
 * Test for MoveFileAction.
 */
public class TestMoveFileAction {
  private static final int DEFAULT_BLOCK_SIZE = 50;
  MiniDFSCluster cluster;
  DistributedFileSystem dfs;
  DFSClient dfsClient;
  SmartContext smartContext;

  static {
    TestBalancer.initTestSetup();
  }

  @Before
  public void init() throws Exception {
    SmartConf conf = new SmartConf();
    initConf(conf);
    cluster = new MiniDFSCluster.Builder(conf)
            .numDataNodes(5)
            .storagesPerDatanode(3)
            .storageTypes(new StorageType[]{StorageType.DISK, StorageType.ARCHIVE,
                    StorageType.SSD})
            .build();
    cluster.waitActive();
    dfs = cluster.getFileSystem();
    dfsClient = dfs.getClient();
    smartContext = new SmartContext(conf);
  }

  static void initConf(Configuration conf) {
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, DEFAULT_BLOCK_SIZE);
    conf.setInt(DFSConfigKeys.DFS_BYTES_PER_CHECKSUM_KEY, DEFAULT_BLOCK_SIZE);
    conf.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1L);
    conf.setLong(DFSConfigKeys.DFS_NAMENODE_REPLICATION_INTERVAL_KEY, 1L);
    conf.setLong(DFSConfigKeys.DFS_BALANCER_MOVEDWINWIDTH_KEY, 2000L);
  }

  @Test(timeout = 300000)
  public void testParallelMovers() throws Exception {
    try {
      final String file1 = "/testParallelMovers/file1";
      final String file2 = "/testParallelMovers/file2";
      Path dir = new Path("/testParallelMovers");
      dfs.mkdirs(dir);
      // write to DISK
      dfs.setStoragePolicy(dir, "HOT");
      final FSDataOutputStream out1 = dfs.create(new Path(file1));
      out1.writeChars("testParallelMovers1");
      out1.close();
      final FSDataOutputStream out2 = dfs.create(new Path(file2));
      out2.writeChars("testParallelMovers2");
      out2.close();

      // schedule move to ARCHIVE or SSD
      MoveFileAction action1 = new MoveFileAction();
      action1.setDfsClient(dfsClient);
      action1.setContext(smartContext);
      action1.init(new String[] {file1, "COLD"});
      MoveFileAction action2 = new MoveFileAction();
      action2.setDfsClient(dfsClient);
      action2.setContext(smartContext);
      action2.init(new String[] {file2, "ALL_SSD"});
      ActionStatus status1 = action1.getActionStatus();
      ActionStatus status2 = action2.getActionStatus();

      action1.run();
      action2.run();

      while (!status1.isFinished() || !status2.isFinished()) {
        System.out.println("Mover 1 running time : " +
                StringUtils.formatTime(status1.getRunningTime()));
        System.out.println("Mover 2 running time : " +
                StringUtils.formatTime(status2.getRunningTime()));
        Thread.sleep(3000);
      }
      Assert.assertTrue(status1.isSuccessful());
      Assert.assertTrue(status2.isSuccessful());
      System.out.println("Mover 1 total running time : " +
              StringUtils.formatTime(status1.getRunningTime()));
      System.out.println("Mover 2 total running time : " +
              StringUtils.formatTime(status2.getRunningTime()));
    } finally {
      cluster.shutdown();
    }
  }

  @Test
  public void testMoverPercentage() throws Exception {
    try {
      final String file1 = "/testParallelMovers/file1";
      final String file2 = "/testParallelMovers/child/file2";
      String dir = "/testParallelMovers";
      dfs.mkdirs(new Path(dir));
      dfs.mkdirs(new Path("/testParallelMovers/child"));

      // write to DISK
      dfs.setStoragePolicy(new Path(dir), "HOT");
      final FSDataOutputStream out1 = dfs.create(new Path(file1), (short)5);
      final String string1 = "testParallelMovers1";
      out1.writeChars(string1);
      out1.close();
      final FSDataOutputStream out2 = dfs.create(new Path(file2));
      final String string2 = "testParallelMovers212345678901234567890";
      out2.writeChars(string2);
      out2.close();

      // schedule move to ALL_SSD
      long totalSize1 = string1.length()*2*5;
      long blockNum1 = 1*5;
      long totalSize2 = string2.length()*2*3;
      long blockNum2 = 2*3;
      scheduleMoverWithPercentage(dir, "ALL_SSD", totalSize1 + totalSize2,
              blockNum1 + blockNum2);

      // schedule move to ONE_SSD
      totalSize1 = string1.length()*2*4;
      blockNum1 = 1*4;
      totalSize2 = string2.length()*2*2;
      blockNum2 = 2*2;
      scheduleMoverWithPercentage(dir, "ONE_SSD", totalSize1 + totalSize2,
              blockNum1 + blockNum2);
    } finally {
      cluster.shutdown();
    }
  }

  private void scheduleMoverWithPercentage(String dir, String storageType,
      long totalSize, long totolBlocks) throws Exception {
    MoveFileAction moveFileAction = new MoveFileAction();
    moveFileAction.setDfsClient(dfsClient);
    moveFileAction.setContext(smartContext);
    moveFileAction.init(new String[] {dir, storageType});
    ActionStatus status = moveFileAction.getActionStatus();
    moveFileAction.run();

    if (status instanceof MoverStatus) {
      MoverStatus moverStatus = (MoverStatus)status;
      while (!moverStatus.isFinished()) {
        System.out.println("Mover running time : " +
                StringUtils.formatTime(moverStatus.getRunningTime()));
        System.out.println("Moved/Total : " + moverStatus.getMovedBlocks()
                + "/" + moverStatus.getTotalBlocks());
        System.out.println("Move percentage : " +
                moverStatus.getPercentage()*100 + "%");
        Assert.assertTrue(moverStatus.getPercentage() <= 1);
        Thread.sleep(1000);
      }
      System.out.println("Mover is finished.");
      Assert.assertEquals(1.0f, moverStatus.getPercentage(), 0.00001f);
      Assert.assertEquals(totalSize, moverStatus.getTotalSize());
      Assert.assertEquals(totolBlocks, moverStatus.getTotalBlocks());
    }
  }
}
