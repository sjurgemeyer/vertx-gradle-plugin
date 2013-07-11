package com.darylteo.gradle.plugins.vertx

import groovy.json.*

import java.util.concurrent.CountDownLatch

import org.gradle.api.*
import org.gradle.api.tasks.Copy
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.platform.PlatformLocator

import com.darylteo.gradle.plugins.vertx.handlers.VertxPropertiesHandler

/**
 * Plugin responsible for configuring a vertx enabled project
 *
 * Required Properties
 *  * vertxVersion - version of Vertx to use
 * @author Daryl Teo
 */
class VertxProjectPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.convention.plugins.projectPlugin = new ProjectPluginConvention(project)

    // classloader hack : dependency cannot be loaded after buildscript is evaluated.
    PlatformLocator.factory.createPlatformManager()

    configureProject project
    registerIncludes project
    addModuleTasks project
  }

  private void configureProject(Project project) {
    project.with {
      println "Configuring $it"

      // configure language plugins
      if(vertx.language in ['java', 'groovy', 'scala']){
        apply plugin: vertx.language
      } else {
        apply plugin: 'java'
      }

      sourceCompatibility = '1.7'
      targetCompatibility = '1.7'

      repositories {
        mavenCentral()
        maven { url 'https://oss.sonatype.org/content/repositories/snapshots' }
      }

      configurations {
        provided      // compile time dependencies that should not be packed in

        vertxcore     // holds all core vertx jars
        vertxincludes // holds all included modules
        vertxlibs     // holds all libs from included modules

        provided.extendsFrom vertxcore
        provided.extendsFrom vertxincludes
        provided.extendsFrom vertxlibs

        compile.extendsFrom provided
      }

      /* Module Configuration */
      afterEvaluate {
        dependencies {
          vertxcore "io.vertx:vertx-core:${vertx.version}"
          vertxcore "io.vertx:vertx-platform:${vertx.version}"

          vertxcore "io.vertx:testtools:${vertx.version}"
        }

        // Configuring Classpath
        sourceSets {
          all { compileClasspath += configurations.provided }
        }

        // Map the 'provided' dependency configuration to the appropriate IDEA visibility scopes.
        plugins.withType(IdeaPlugin) {
          idea {
            module {
              scopes.PROVIDED.plus += configurations.provided
              scopes.COMPILE.minus += configurations.provided
              scopes.TEST.minus += configurations.provided
              scopes.RUNTIME.minus += configurations.provided
            }
          }
        }
      }

    }
  }

  private void addModuleTasks(Project project){
    project.with {
      task('generateModJson') {
        def confdir = file("$buildDir/conf")
        def modjson = file("$confdir/mod.json")
        outputs.file modjson

        doLast {
          confdir.mkdirs()
          modjson.createNewFile()

          modjson << JsonOutput.toJson(vertx.config)
        }
      }

      task('copyMod', dependsOn: [
        classes,
        generateModJson
      ], type: Copy) {
        group = 'vert.x'
        description = 'Assemble the module into the local mods directory'

        afterEvaluate {
          into rootProject.file("mods/${project.moduleName}")

          sourceSets.all {
            if (it.name != 'test'){
              from it.output
            }
          }
          from generateModJson

          // and then into module library directory
          into ('lib') {
            from configurations.compile
            exclude { it.file in configurations.provided.files }
          }
        }
      }

      test { dependsOn copyMod }

    }
  }

  private void registerIncludes(Project project) {
    project.with {
      afterEvaluate {
        // resolve the includes
        def platform = PlatformLocator.factory.createPlatformManager()
        def latch = new CountDownLatch(vertx.config?.includes?.size() ?: 0);

        vertx.config?.includes?.each { notation ->
          println "Installing Module $notation..."

          // Install the Module
          platform.installModule(notation, new AsyncResultHandler<Void>() {
              public void handle(AsyncResult<Void> result) {
                if(result.succeeded()){
                  println "Installation of $notation: successful"
                  addDeps()
                } else {
                  def message = result.cause().message;

                  // TODO: create custom exception types for this
                  if(!message.contains('Module is already installed')) {
                    println "Installation of $notation: ${message}"
                  } else {
                    println "Installation of $notation: already installed"
                    addDeps()
                  }
                }

                latch.countDown();
              }

              private void addDeps() {
                println "Adding ${notation} to dependencies"
                project.dependencies {
                  vertxincludes project.rootProject.files("mods/${notation}")
                  vertxlibs project.rootProject.fileTree("mods/${notation}") { include 'lib/*.jar' }
                }
              }
            })
        }

        latch.await();
      }
    }
  }

  private class ProjectPluginConvention {
    private Project project
    private VertxPropertiesHandler properties

    ProjectPluginConvention(Project project){
      this.project = project
      this.properties = new VertxPropertiesHandler()
    }

    String getModuleName() {
      return "${project.group}~${project.name}~${project.version}"
    }

    def vertx(Closure closure) {
      closure.setDelegate(properties)
      closure.resolveStrategy = Closure.DELEGATE_FIRST
      closure(properties)
    }

    def getVertx() {
      return properties
    }
  }

}
