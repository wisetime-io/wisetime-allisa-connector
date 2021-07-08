/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

buildscript {
  repositories {
    mavenCentral()
    maven {
      // WT published releases
      setUrl("https://s3.eu-central-1.amazonaws.com/artifacts.wisetime.com/mvn2/plugins")
      content {
        includeGroup("io.wisetime")
      }
    }
  }
  dependencies {
    // https://github.com/GoogleContainerTools/jib/issues/1018
    classpath("org.apache.httpcomponents:httpclient:4.5.12") {
      setForce(true)
    }
  }
}

plugins {
  java
  idea
  id("application")
  id("maven")
  id("maven-publish")
  id("io.freefair.lombok") version "5.3.0"
  id("fr.brouillard.oss.gradle.jgitver") version "0.9.1"
  id("com.google.cloud.tools.jib") version "2.8.0"
  id("com.github.ben-manes.versions") version "0.27.0"
  id("io.wisetime.versionChecker") version "10.11.62"
}

apply(from = "$rootDir/gradle/conf/checkstyle.gradle")
apply(from = "$rootDir/gradle/conf/jacoco.gradle")

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11))
    vendor.set(JvmVendorSpec.ADOPTOPENJDK)
    implementation.set(JvmImplementation.J9)
  }
  consistentResolution {
    useCompileClasspathVersions()
  }
}

group = "io.wisetime"
application {
  mainClass.set("io.wisetime.connector.allisa.ConnectorLauncher")
}

jib {
  val targetArch = project.properties["targetArch"] ?: ""
  if (targetArch == "arm64v8") {
    from {
      image = "arm64v8/openjdk:11.0.8-jdk-buster"
    }
    to {
      project.afterEvaluate { // <-- so we evaluate version after it has been set
        image = "wisetime/wisetime-allisa-connector-arm64v8:${project.version}"
      }
    }
  } else {
    from {
      image = "gcr.io/wise-pub/connect-java-11-j9@sha256:98ec5f00539bdffeb678c3b4a34c07c77e4431395286ecc6a083298089b3d0ec"
    }
    to {
      project.afterEvaluate { // <-- so we evaluate version after it has been set
        image = "wisetime/wisetime-allisa-connector:${project.version}"
      }
    }
  }
}

repositories {
  mavenCentral()
  maven {
    // WiseTime artifacts
    setUrl("https://s3.eu-central-1.amazonaws.com/artifacts.wisetime.com/mvn2/releases")
    content {
      includeGroup("io.wisetime")
    }
  }
}

tasks.withType(com.google.cloud.tools.jib.gradle.JibTask::class.java) {
  dependsOn(tasks.compileJava)
}

val taskRequestString = gradle.startParameter.taskRequests.toString()
if (taskRequestString.contains("dependencyUpdates")) {
  // add exclusions for reporting on updates and vulnerabilities
  apply(from = "$rootDir/gradle/versionPluginConfig.gradle")
}

dependencies {
  implementation("io.wisetime:wisetime-connector:3.0.10")
  implementation("com.google.inject:guice:5.0.1") {
    exclude(group = "com.google.guava", module = "guava")
  }
  implementation("com.google.guava:guava:30.0-jre")

  implementation("com.squareup.retrofit2:retrofit:2.6.2")
  implementation("com.squareup.retrofit2:converter-gson:2.6.2")
  implementation("com.squareup.retrofit2:converter-scalars:2.6.2")

  testImplementation("org.junit.jupiter:junit-jupiter:5.4.2")
  testImplementation("org.mockito:mockito-core:2.27.0")
  testImplementation("org.assertj:assertj-core:3.12.2")
  testImplementation("com.github.javafaker:javafaker:0.17.2") {
    exclude(group = "org.apache.commons", module = "commons-lang3")
  }
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    // "passed", "skipped", "failed"
    events("skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

val jacksonVersion = "2.12.3"

configurations.all {
  resolutionStrategy {
    // fail eagerly on version conflict (includes transitive dependencies)
    // e.g. multiple different versions of the same dependency (group and name are equal)
    failOnVersionConflict()

    // force certain versions of dependencies (including transitive)
    //  *append new forced modules:
    force(
      "com.google.guava:guava:30.1-jre",
      "com.google.code.gson:gson:2.8.6",
      "joda-time:joda-time:2.10.10",
      "org.apache.commons:commons-lang3:3.12.0",
      "com.fasterxml.jackson.core:jackson-databind:$jacksonVersion",
      "com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:$jacksonVersion",
      "commons-codec:commons-codec:1.11",
      "org.slf4j:slf4j-api:1.7.30",
      "org.apache.httpcomponents:httpclient:4.5.9"
    )
  }
}
tasks.clean {
  delete("${projectDir}/out")
}

jgitver {
  autoIncrementPatch(false)
  strategy(fr.brouillard.oss.jgitver.Strategies.PATTERN)
  versionPattern("\${meta.CURRENT_VERSION_MAJOR}.\${meta.CURRENT_VERSION_MINOR}.\${meta.COMMIT_DISTANCE}")
  regexVersionTag("v(\\d+\\.\\d+(\\.0)?)")
}

publishing {
  repositories {
    maven {
      setUrl("s3://artifacts.wisetime.com/mvn2/releases")
      authentication {
        val awsIm by registering(AwsImAuthentication::class)
      }
    }
  }

  publications {
    register("mavenJava", MavenPublication::class) {
      artifactId = "wisetime-allisa-connector"
      from(components["java"])
    }
  }
}

tasks.register<DefaultTask>("printVersionStr") {
  doLast {
    println("${project.version}")
  }
}
