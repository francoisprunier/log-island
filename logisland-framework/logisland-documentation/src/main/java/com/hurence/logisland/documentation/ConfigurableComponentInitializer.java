/**
 * Copyright (C) 2016 Hurence (bailet.thomas@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hurence.logisland.documentation;

import com.hurence.logisland.component.ConfigurableComponent;
import com.hurence.logisland.component.InitializationException;

/**
 * An interface for initializing and tearing down a ConfigurableComponent. It is up to the
 * implementer to call "init" so that you can call
 * ConfigurableComponent.getPropertyDescriptors()
 *
 */
public interface ConfigurableComponentInitializer {

    /**
     * Initializes a configurable component to the point that you can call
     * getPropertyDescriptors() on it
     *
     * @param component the component to initialize
     * @throws InitializationException if the component could not be initialized
     */
    void initialize(ConfigurableComponent component) throws InitializationException;

    /**
     * Calls the lifecycle methods that should be called when a flow is shutdown.
     *
     * @param component the component to initialize
     */
    void teardown(ConfigurableComponent component);
}
