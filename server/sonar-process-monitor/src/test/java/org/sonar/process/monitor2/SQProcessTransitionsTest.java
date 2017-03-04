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

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.process.monitor2.SQProcessTransitions.State.INIT;
import static org.sonar.process.monitor2.SQProcessTransitions.State.STARTED;
import static org.sonar.process.monitor2.SQProcessTransitions.State.STARTING;
import static org.sonar.process.monitor2.SQProcessTransitions.State.STOPPED;
import static org.sonar.process.monitor2.SQProcessTransitions.State.STOPPING;

public class SQProcessTransitionsTest {

  @Test
  public void equals_and_hashcode() {
    SQProcessTransitions init = new SQProcessTransitions();
    assertThat(init.getState()).isEqualTo(INIT);
    assertThat(init.equals(init)).isTrue();
    assertThat(init.equals(null)).isFalse();
    assertThat(init.equals("INIT")).isFalse();
    assertThat(init.hashCode()).isEqualTo(new SQProcessTransitions().hashCode());

    SQProcessTransitions starting = new SQProcessTransitions();
    assertThat(starting.tryToMoveTo(STARTING)).isTrue();
    assertThat(starting.equals(init)).isFalse();
    assertThat(starting.equals(starting)).isTrue();
    assertThat(starting.getState()).isEqualTo(STARTING);
  }


  @Test
  public void can_move_to_STOPPING_from_STARTING_STARTED_only() {
    for (SQProcessTransitions.State state : SQProcessTransitions.State.values()) {
      boolean tryToMoveTo = newTransition(state).tryToMoveTo(STOPPING);
      if (state == STARTING || state == STARTED) {
        assertThat(tryToMoveTo).describedAs("from state " + state).isTrue();
      } else {
        assertThat(tryToMoveTo).describedAs("from state " + state).isFalse();
      }
    }
  }

  @Test
  public void no_state_can_not_move_to_itself() {
    for (SQProcessTransitions.State state : SQProcessTransitions.State.values()) {
      assertThat(newTransition(state).tryToMoveTo(state)).isFalse();
    }
  }

  private SQProcessTransitions newTransition(SQProcessTransitions.State state) {
    SQProcessTransitions transition = new SQProcessTransitions();
    switch (state) {
      case INIT:
        return transition;
      case STARTED:
        transition.tryToMoveTo(STARTING);
        transition.tryToMoveTo(STARTED);
        return transition;
      case STOPPED:
        transition.tryToMoveTo(STARTING);
        transition.tryToMoveTo(STOPPING);
        transition.tryToMoveTo(STOPPED);
        return transition;
      case STARTING:
        transition.tryToMoveTo(STARTING);
        return transition;
      case STOPPING:
        transition.tryToMoveTo(STARTING);
        transition.tryToMoveTo(STOPPING);
        return transition;
      default:
          throw new IllegalArgumentException("Unsupported state " + state);
    }
  }
}
