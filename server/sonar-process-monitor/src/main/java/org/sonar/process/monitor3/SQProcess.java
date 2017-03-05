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

package org.sonar.process.monitor3;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.process.Lifecycle;
import org.sonar.process.ProcessCommands;
import org.sonar.process.ProcessId;
import org.sonar.process.ProcessUtils;

import static java.lang.String.format;

public class SQProcess {

  private static final Logger LOG = LoggerFactory.getLogger(SQProcess.class);

  private final ProcessId processId;
  private final List<ProcessStateListener> stateListeners;
  private final Lifecycle lifecycle = new Lifecycle();

  private Process process;
  private ProcessCommands commands;
  private StreamGobbler gobbler;
  private StopWatcher stopWatcher;
  private OperationalWatcher operationalWatcher;

  public SQProcess(ProcessId processId, List<ProcessStateListener> stateListeners) {
    this.processId = processId;
    this.stateListeners = stateListeners;
  }

  void monitor(Process process, ProcessCommands commands) {
    this.process = process;
    this.commands= commands;
    this.gobbler = new StreamGobbler(process.getInputStream(), processId.getKey());
    this.gobbler.start();
    this.stopWatcher = new StopWatcher();
    this.stopWatcher.start();
    this.operationalWatcher = new OperationalWatcher();
    this.operationalWatcher.start();
  }

  public ProcessId getProcessId() {
    return processId;
  }

  public Lifecycle getLifecycle() {
    return lifecycle;
  }

  /**
   * Sends kill signal and awaits termination. No guarantee that process is gracefully terminated (=shutdown hooks
   * executed). It depends on OS.
   */
  public void stop(long timeout, TimeUnit timeoutUnit) {
    if (lifecycle.tryToMoveTo(Lifecycle.State.STOPPING) && ProcessUtils.isAlive(process)) {
      try {
        ProcessUtils.sendKillSignal(process);
        // graceful stop requested.
        process.waitFor(timeout, timeoutUnit);
      } catch (InterruptedException e) {
        // can't wait for the termination of process. Let's assume it's down.
        LOG.warn(format("Interrupted while stopping process %s", processId), e);
        Thread.currentThread().interrupt();
      }
      forceStop();
    }
  }

  // TODO test that can be called multiple times
  public void forceStop() {
    operationalWatcher.interrupt();
    stopWatcher.interrupt();
    process.destroyForcibly();
    ProcessUtils.closeStreams(process);
    StreamGobbler.waitUntilFinish(gobbler);
    gobbler.interrupt();
    changeStateToStopped();
  }

  private void changeStateToStopped() {
    if (lifecycle.tryToMoveTo(Lifecycle.State.STOPPED)) {
      stateListeners.forEach(l -> l.onProcessStop(processId));
    }
  }

  private void changeStateToOperational() {
    if (lifecycle.tryToMoveTo(Lifecycle.State.OPERATIONAL)) {
      stateListeners.forEach(l -> l.onProcessOperational(processId));
    }
  }

  public boolean askedForRestart() {
    return commands.askedForRestart();
  }

  public boolean askedForStop() {
    return commands.askedForStop();
  }

  @Override
  public String toString() {
    return format("Process[%s]", processId.getKey());
  }

  /**
   * This thread blocks as long as the monitored process is physically alive.
   * It avoids from executing {@link Process#exitValue()} at a fixed rate :
   * <ul>
   *   <li>no usage of exception for flow control. Indeed {@link Process#exitValue()} throws an exception
   *   if process is alive. There's no method <code>Process#isAlive()</code></li>
   *   <li>no delay, instantaneous notification that process is down</li>
   * </ul>
   */
  private class StopWatcher extends Thread {
    StopWatcher() {
      // this name is different than Thread#toString(), which includes name, priority
      // and thread group
      // -> do not override toString()
      super(format("StopWatcher[%s]", processId.getKey()));
    }

    @Override
    public void run() {
      try {
        process.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // stop watching process
      }
      forceStop();
    }
  }

  private class OperationalWatcher extends Thread {
    OperationalWatcher() {
      // this name is different than Thread#toString(), which includes name, priority
      // and thread group
      // -> do not override toString()
      super(format("OperationalWatcher[%s]", processId.getKey()));
    }

    @Override
    public void run() {
      System.out.println("-----");
      try {
        while (!isOperational()) {
          System.out.println("not operational " + processId);
          Thread.sleep(250L);
        }
        System.out.println("operational " + processId);
        changeStateToOperational();
      } catch (InterruptedException e) {
        // stop watching process
        Thread.currentThread().interrupt();
        forceStop();
      }
    }

    private boolean isOperational() {
      return ProcessUtils.isAlive(process) && commands.isOperational();
    }
  }
}
