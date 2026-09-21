plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
}

// AGP's host-side Unified Test Platform is isolated from shipped app dependencies.
// Keep its transport/crypto dependencies on reviewed, ABI-compatible patched lines.
// Never apply these rules to releaseRuntimeClasspath or consumer publications.
// Recognize only stable versions on these compatible lines. Never downgrade a
// newer dependency or guess the ordering of dynamic/prerelease/unknown versions;
// the advisory scan remains responsible for rejecting an unsafe resolved graph.
object HostToolingDependencyFloor {
    fun select(group: String, name: String, version: String?): String? {
        if (version == null) return null
        fun component(pattern: String): Int? = Regex(pattern).matchEntire(version)
            ?.groupValues?.get(1)?.toIntOrNull()
        return when {
            group == "io.netty" && (component("4\\.1\\.([0-9]+)\\.Final") ?: Int.MAX_VALUE) < 137 -> "4.1.137.Final"
            group == "org.bouncycastle" && name in setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on") &&
                (component("1\\.([0-9]+)(?:\\.[0-9]+)?") ?: Int.MAX_VALUE) < 85 -> "1.85"
            group == "org.apache.commons" && name == "commons-lang3" &&
                (component("3\\.([0-9]+)\\.[0-9]+") ?: Int.MAX_VALUE) < 18 -> "3.18.0"
            group == "org.apache.httpcomponents" && name == "httpclient" &&
                (component("4\\.5\\.([0-9]+)") ?: Int.MAX_VALUE) < 14 -> "4.5.14"
            else -> null
        }
    }
}

tasks.register("verifyHostToolingDependencyFloors") {
    group = "verification"
    description = "Verify host-only dependency floors never downgrade newer or unknown versions."
    doLast {
        data class Rule(val group: String, val name: String, val older: String, val floor: String, val untouched: List<String>)
        val rules = listOf(
            Rule("io.netty", "netty-handler", "4.1.110.Final", "4.1.137.Final", listOf("4.1.138.Final", "4.2.0.Final", "4.1.138.CR1", "4.1.+", "[4.1.110.Final,4.2.0.Final)", "4.1.99999999999999999999.Final")),
            Rule("org.bouncycastle", "bcprov-jdk18on", "1.79", "1.85", listOf("1.85.1", "1.86", "2.0", "1.86-beta", "1.+")),
            Rule("org.apache.commons", "commons-lang3", "3.16.0", "3.18.0", listOf("3.18.1", "3.19.0", "4.0.0", "3.19.0-RC1", "3.+")),
            Rule("org.apache.httpcomponents", "httpclient", "4.5.6", "4.5.14", listOf("4.5.15", "4.6.0", "5.0.0", "4.5.15-beta1", "4.5.+")),
        )
        for (rule in rules) {
            check(HostToolingDependencyFloor.select(rule.group, rule.name, rule.older) == rule.floor)
            for (version in rule.untouched + rule.floor) {
                check(HostToolingDependencyFloor.select(rule.group, rule.name, version) == null) {
                    "host dependency floor must not override ${rule.group}:${rule.name}:$version"
                }
            }
            check(HostToolingDependencyFloor.select(rule.group, rule.name, null) == null)
            check(HostToolingDependencyFloor.select("unrelated.group", rule.name, rule.older) == null)
        }
        check(HostToolingDependencyFloor.select("org.bouncycastle", "unrelated-artifact", "1.79") == null)
    }
}

subprojects {
    configurations.configureEach {
        if (name.startsWith("_internal-unified-test-platform-")) {
            resolutionStrategy.eachDependency {
                val patched = HostToolingDependencyFloor.select(requested.group, requested.name, requested.version)
                if (patched != null) {
                    useVersion(patched)
                    because("host-only UTP security remediation; shipped SDK dependencies are unchanged")
                }
            }
        }
    }
}
