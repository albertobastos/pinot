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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.runtime.operator.exchange;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.apache.pinot.query.mailbox.SendingMailbox;
import org.apache.pinot.query.runtime.blocks.BlockSplitter;
import org.apache.pinot.query.runtime.blocks.MseBlock;


/**
 * Sends blocks to a specific server, with the expectation that only one
 * server is ever on the receiving end.
 */
class SingletonExchange extends BlockExchange {

  SingletonExchange(List<SendingMailbox> sendingMailboxes, BlockSplitter splitter,
      Function<List<SendingMailbox>, Integer> statsIndexChooser) {
    super(sendingMailboxes, splitter, statsIndexChooser);
    Preconditions.checkArgument(sendingMailboxes.size() == 1, "Expect single mailbox in Singleton Exchange");
  }

  SingletonExchange(List<SendingMailbox> sendingMailboxes, BlockSplitter splitter) {
    this(sendingMailboxes, splitter, RANDOM_INDEX_CHOOSER);
  }

  @Override
  protected void route(List<SendingMailbox> sendingMailboxes, MseBlock.Data block)
      throws IOException, TimeoutException {
    sendBlock(sendingMailboxes.get(0), block);
  }
}
