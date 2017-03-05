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
package org.sonar.application2;

import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.sonar.process.Lifecycle;
import org.sonar.process.ProcessId;
import org.sonar.process.monitor3.JavaCommand;
import org.sonar.process.monitor3.JavaProcessLauncher;
import org.sonar.process.monitor3.ProcessStateListener;
import org.sonar.process.monitor3.SQProcess;

import static java.util.Collections.singletonList;

public class SchedulerImpl implements Scheduler, ProcessStateListener {

  private final JavaCommandFactory javaCommandFactory;
  private final JavaProcessLauncher javaProcessLauncher;
  private final AppState appState;
  private final NodeLifecycle nodeLifecycle = new NodeLifecycle();

  private final CountDownLatch keepAlive = new CountDownLatch(1);
  private final AtomicBoolean restartRequested = new AtomicBoolean(false);
  private final EnumMap<ProcessId, SQProcess> processesById = new EnumMap<>(ProcessId.class);
  private final AtomicInteger operationalCountDown = new AtomicInteger();
  private final AtomicInteger stopCountDown = new AtomicInteger();
  private StopperThread stopperThread;

  public SchedulerImpl(JavaCommandFactory javaCommandFactory, JavaProcessLauncher javaProcessLauncher, AppState appState) {
    this.javaCommandFactory = javaCommandFactory;
    this.javaProcessLauncher = javaProcessLauncher;
    this.appState = appState;
  }

  @Override
  public void schedule() {
    if (nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STARTING)) {
      if (javaCommandFactory.isEsEnabled()) {
        SQProcess process = new SQProcess(ProcessId.ELASTICSEARCH, singletonList(this));
        processesById.put(process.getProcessId(), process);
      }
      if (javaCommandFactory.isWebEnabled()) {
        SQProcess process = new SQProcess(ProcessId.WEB_SERVER, singletonList(this));
        processesById.put(process.getProcessId(), process);
      }
      if (javaCommandFactory.isCeEnabled()) {
        SQProcess process = new SQProcess(ProcessId.COMPUTE_ENGINE, singletonList(this));
        processesById.put(process.getProcessId(), process);
      }
      operationalCountDown.set(processesById.size());
      stopCountDown.set(processesById.size());

      tryToStartAll();
    }
  }

  private void tryToStartAll() {
    try {
      tryToStartEs();
      tryToStartWeb();
      tryToStartCe();
    } catch (RuntimeException e) {
      terminate();
    }
  }

  private void tryToStartEs() {
    tryToStartProcess(ProcessId.ELASTICSEARCH,
      p -> true,
      () -> javaCommandFactory.createEsCommand());
  }

  private void tryToStartWeb() {
    tryToStartProcess(ProcessId.WEB_SERVER,
      p -> appState.isOperational(ProcessId.ELASTICSEARCH),
      () -> javaCommandFactory.createWebCommand(true));
  }

  private void tryToStartCe() {
    tryToStartProcess(ProcessId.COMPUTE_ENGINE,
      p -> appState.isOperational(ProcessId.WEB_SERVER),
      () -> javaCommandFactory.createCeCommand());
  }

  private void tryToStartProcess(ProcessId processId, Predicate<SQProcess> startupCondition, Supplier<JavaCommand> commandSupplier) {
    SQProcess process = processesById.get(processId);
    if (process != null &&
      startupCondition.test(process) &&
      process.getLifecycle().tryToMoveTo(Lifecycle.State.STARTING)) {
      try {
        JavaCommand command = commandSupplier.get();
        javaProcessLauncher.launch(process, command);
      } catch (RuntimeException e) {
        // failed to start command
        terminate();
      }
    }
  }

  private void tryToStopAll() {
    tryToStopProcess(ProcessId.COMPUTE_ENGINE);
    tryToStopProcess(ProcessId.WEB_SERVER);
    tryToStopProcess(ProcessId.ELASTICSEARCH);
  }

  private void tryToStopProcess(ProcessId processId) {
    SQProcess process = processesById.get(processId);
    if (process != null) {
      process.stop(1, TimeUnit.MINUTES);
    }
  }

  @Override
  public void terminate() {
    // disable pending restart request, if any
    restartRequested.set(false);

    if (nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STOPPING)) {
      System.out.println("stopping...");
      tryToStopAll();
      System.out.println("waiting for stopped...");
    }
    awaitTermination();
  }

  @Override
  public void awaitTermination() {
    try {
      keepAlive.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void onProcessOperational(ProcessId processId) {
    // TODO log "xxx is up"
    appState.setOperational(processId);
    if (operationalCountDown.decrementAndGet() == 0 && nodeLifecycle.tryToMoveTo(NodeLifecycle.State.OPERATIONAL)) {
      System.out.println("SonarQube is up");
    } else if (nodeLifecycle.getState() == NodeLifecycle.State.STARTING) {
      tryToStartAll();
    }
  }

  @Override
  public void onProcessStop(ProcessId processId) {
    if (stopCountDown.decrementAndGet() == 0 && nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STOPPED)) {
      // all processes are stopped
      System.out.println("SonarQube is stopped");
      if (restartRequested.compareAndSet(true, false) && nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STARTING)) {
        // TODO start async
      } else {
        keepAlive.countDown();
      }

    } else if (nodeLifecycle.tryToMoveTo(NodeLifecycle.State.STOPPING)) {
      // this is the first process stopping
      stopperThread = new StopperThread();
      stopperThread.start();
    }
  }

  private class StarterThread extends Thread {
    public StarterThread() {
      super("Starter");
    }

    @Override
    public void run() {
      tryToStartAll();
    }
  }

  private class StopperThread extends Thread {
    public StopperThread() {
      super("Stopper");
    }

    @Override
    public void run() {
      tryToStopAll();
    }
  }
}
