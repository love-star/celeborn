/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.service.deploy.worker.shuffledb;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.rocksdb.RocksIterator;

/**
 * RocksDB implementation of `DBIterator`.
 *
 * <p>Note: code copied from Apache Spark.
 */
public class RocksDBIterator implements DBIterator {

  private final RocksIterator it;

  private boolean checkedNext;

  private boolean closed;

  private Map.Entry<byte[], byte[]> next;

  public RocksDBIterator(RocksIterator it) {
    this.it = it;
  }

  @Override
  public boolean hasNext() {
    if (!checkedNext && !closed) {
      next = loadNext();
      checkedNext = true;
    }
    if (!closed && next == null) {
      try {
        close();
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
    return next != null;
  }

  @Override
  public Map.Entry<byte[], byte[]> next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    checkedNext = false;
    Map.Entry<byte[], byte[]> ret = next;
    next = null;
    return ret;
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      it.close();
      closed = true;
      next = null;
    }
  }

  @Override
  public void seek(byte[] key) {
    it.seek(key);
  }

  private Map.Entry<byte[], byte[]> loadNext() {
    if (it.isValid()) {
      Map.Entry<byte[], byte[]> nextEntry = new AbstractMap.SimpleEntry<>(it.key(), it.value());
      it.next();
      return nextEntry;
    }
    return null;
  }
}
