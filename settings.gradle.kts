pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack hosts the nextlib media3 extension — community build of
        // the official Media3 ffmpeg renderer (Google ships that one as
        // source-only because of ffmpeg's GPL licensing complexities, so
        // we either build it ourselves or pull a third-party Maven
        // mirror). Used by NextRenderersFactory to substitute the
        // ffmpeg AAC decoder for the default platform one — handles
        // odd source sample rates (24 kHz on FOX USA) that TCL's
        // AudioTrack outright rejects.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MovieBoxTV"
include(":app")
// Pure-Kotlin JVM module that both :app (Android) and :desktop (JVM)
// depend on. Lives here so a patch to the H5 stack, crypto signing, or
// any shared logic is applied ONCE and benefits both apps. Android-
// specific glue (logging, key-value storage) is abstracted behind small
// interfaces; each app provides its own implementation.
include(":shared")
// Desktop (Mac + Windows) build target via Compose Multiplatform.
include(":desktop")
