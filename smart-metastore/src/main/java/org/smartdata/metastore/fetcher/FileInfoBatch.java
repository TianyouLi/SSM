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
package org.smartdata.metastore.fetcher;

import org.smartdata.model.FileInfo;

public class FileInfoBatch {
  private FileInfo[] fileInfos;
  private final int batchSize;
  private int index;

  public FileInfoBatch(int batchSize) {
    this.batchSize = batchSize;
    this.fileInfos = new FileInfo[batchSize];
    this.index = 0;
  }

  public void add(FileInfo status) {
    this.fileInfos[index] = status;
    index += 1;
  }

  public boolean isFull() {
    return index == batchSize;
  }

  public int actualSize() {
    return this.index;
  }

  public FileInfo[] getFileInfos() {
    return this.fileInfos;
  }
}

