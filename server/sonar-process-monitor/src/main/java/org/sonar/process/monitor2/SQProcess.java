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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.process.ProcessCommands;
import org.sonar.process.ProcessId;
import org.sonar.process.ProcessUtils;

public class SQProcess {
  enum State {
    INIT, UP, OPERATIONAL, STOPPED, ASKED_FOR_RESTART, ASKED_FOR_SHUTDOWN
  }

  private static final Logger LOG = LoggerFactory.getLogger(SQProcess.class);

  private final JavaCommand javaCommand;
  private final ProcessCommands commands;
  private final Process process;
  private final StreamGobbler gobbler;

  SQProcess(@Nonnull JavaCommand javaCommand, @Nonnull ProcessCommands commands, @Nonnull Process process, @Nonnull StreamGobbler gobbler) {
    this.javaCommand = javaCommand;
    this.commands = commands;
    this.process = process;
    this.gobbler = gobbler;
  }

  /**
   * The {@link Process}
   */
  Process getProcess() {
    return process;
  }

  public ProcessCommands getCommands() {
    return commands;
  }

  /**
   * Sends kill signal and awaits termination. No guarantee that process is gracefully terminated (=shutdown hooks
   * executed). It depends on OS.
   */
  void stop() {
    if (process.isAlive()) {
      try {
        ProcessUtils.sendKillSignal(process);
        // signal is sent, waiting for shutdown hooks to be executed (or not... it depends on OS)
        process.waitFor();

      } catch (InterruptedException e) {
        // can't wait for the termination of process. Let's assume it's down.
        LOG.warn(String.format("Interrupted while stopping process %s", javaCommand.getProcessId()), e);
        Thread.currentThread().interrupt();
      }
    }
    ProcessUtils.closeStreams(process);
    StreamGobbler.waitUntilFinish(gobbler);
  }


  public ProcessId getProcessId() {
    return javaCommand.getProcessId();
  }

  public State getState() {
    if (!process.isAlive()) {
      return State.STOPPED;
    }
    if (commands.isUp()) {
      return State.UP;
    }
    if (commands.isOperational()) {
      return State.OPERATIONAL;
    }
    if (commands.askedForStop()) {
      return State.ASKED_FOR_SHUTDOWN;
    }
    if (commands.askedForRestart()) {
      return State.ASKED_FOR_RESTART;
    }
    return State.INIT;
  }

  @Override
  public String toString() {
    return String.format("Process[%s]", javaCommand.getProcessId().getKey());
  }
}
