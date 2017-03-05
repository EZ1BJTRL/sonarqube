package org.sonar.process.monitor3;

import org.sonar.process.ProcessId;

public interface ProcessCommander {

  void reset(ProcessId processId);

  boolean isRestartRequested();

  boolean isStopRequested();

  boolean isOperational(ProcessId processId);

  boolean isStarted(ProcessId processId);

}
