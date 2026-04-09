/*
 * Copyright 2026 17Artist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package priv.seventeen.artist.blink.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class BlinkExtension {

    abstract val name: Property<String>
    abstract val version: Property<String>
    abstract val description: Property<String>
    abstract val authors: ListProperty<String>
    abstract val apiVersion: Property<String>
    abstract val depend: ListProperty<String>
    abstract val softDepend: ListProperty<String>

    abstract val libraries: ListProperty<String>
    abstract val kotlinVersion: Property<String>
    abstract val enableScript: Property<Boolean>
    abstract val foliaSupported: Property<Boolean>

    abstract val packageName: Property<String>

    abstract val enableAria: Property<Boolean>

    abstract val enableAsteroid: Property<Boolean>

    abstract val obfuscate: Property<Boolean>

    abstract val obfuscateKeep: ListProperty<String>

    abstract val obfuscateExclude: ListProperty<String>

    init {
        name.convention("")
        version.convention("1.0.0")
        description.convention("")
        authors.convention(emptyList())
        apiVersion.convention("1.20")
        depend.convention(emptyList())
        softDepend.convention(emptyList())
        libraries.convention(emptyList())
        kotlinVersion.convention("1.8.22")
        enableScript.convention(false)
        enableAria.convention(false)
        enableAsteroid.convention(false)
        foliaSupported.convention(false)
        packageName.convention("")
        obfuscate.convention(false)
        obfuscateKeep.convention(emptyList())
        obfuscateExclude.convention(emptyList())
    }
}
