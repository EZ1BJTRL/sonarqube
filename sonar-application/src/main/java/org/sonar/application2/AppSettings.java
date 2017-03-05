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

import java.util.Optional;
import java.util.Properties;
import org.sonar.application.JdbcSettings;
import org.sonar.application.PropsBuilder;
import org.sonar.process.Props;

public class AppSettings {

  private final Properties commandLineArguments;
  private Props allProps;

  private AppSettings(Properties commandLineArguments, Props allProps) {
    this.commandLineArguments = commandLineArguments;
    this.allProps = allProps;
  }

  /**
   * TODO to be removed --> AppLogging should use AppSettings but not Props
   */
  public Props getProps() {
    return allProps;
  }

  public Optional<String> getValue(String key) {
    return Optional.ofNullable(allProps.value(key));
  }

  public static AppSettings forCliArguments(Properties cliArguments) {
    PropsBuilder propsBuilder = new PropsBuilder(cliArguments, new JdbcSettings());
    return new AppSettings(cliArguments, propsBuilder.build());
  }

  public void reload() {
    AppSettings reloaded = forCliArguments(commandLineArguments);

    // TODO fail if path directories are not compatible
    // TODO fail if cluster mode changed (enabled <-> disabled)

    this.allProps = reloaded.allProps;
  }
}
