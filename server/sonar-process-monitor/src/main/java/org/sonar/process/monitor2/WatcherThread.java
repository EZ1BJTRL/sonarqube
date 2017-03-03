/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.process.monitor2;

import javax.annotation.Nonnull;

/**
 * This thread blocks as long as the monitored process is physically alive.
 * It avoids from executing {@link Process#exitValue()} at a fixed rate :
 * <ul>
 *   <li>no usage of exception for flow control. Indeed {@link Process#exitValue()} throws an exception
 *   if process is alive. There's no method <code>Process#isAlive()</code></li>
 *   <li>no delay, instantaneous notification that process is down</li>
 * </ul>
 */
class WatcherThread extends Thread {
  private final SQProcess sqProcess;

  WatcherThread(@Nonnull SQProcess sqProcess) {
    // this name is different than Thread#toString(), which includes name, priority
    // and thread group
    // -> do not override toString()
    super(String.format("Watch[%s]", sqProcess.getProcessId().getKey()));
    this.sqProcess = sqProcess;
  }

  @Override
  public void run() {
    boolean stopped = false;
    while (!stopped) {
      try {
        sqProcess.getProcess().waitFor();
        sqProcess.getCommands().acknowledgeAskForRestart();

        // finalize status of sqProcess
        sqProcess.stop();

        stopped = true;
      } catch (InterruptedException ignored) {
        // continue to watch process
      }
    }
  }
}
