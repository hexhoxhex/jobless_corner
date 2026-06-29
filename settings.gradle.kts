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
// Desktop (Mac + Windows) build target via Compose Multiplatform.
// Lives next to :app — shares this repo, doesn't touch the Android module.
// Currently brings up its own copy of the network + crypto layer; will
// share with :app via a future :shared commonMain module once both are stable.
include(":desktop")
