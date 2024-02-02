include(":core")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

if (System.getenv("CI") == null || System.getenv("CI_PUSH") == "true") {
    // Local development or full build for push

    /**
     * Add or remove modules to load as needed for local development here.
     * To generate multisrc extensions first, run the `:multisrc:generateExtensions` task first.
     */
    loadAllIndividualExtensions()
    // loadIndividualExtension("all", "mangadex")
} else {
    // Running in CI (GitHub Actions)

    val lang = System.getenv("CI_MATRIX_LANG")

    // Loads all extensions
    File(rootDir, "src").eachDir { dir ->
        if (dir.name == lang) {
            dir.eachDir { subdir ->
                val name = ":extensions:individual:${dir.name}:${subdir.name}"
                include(name)
                project(name).projectDir = File("src/${dir.name}/${subdir.name}")
            }
        }
    }
}

fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            val name = ":extensions:individual:${dir.name}:${subdir.name}"
            include(name)
            project(name).projectDir = File("src/${dir.name}/${subdir.name}")
        }
    }
}

fun loadIndividualExtension(lang: String, name: String) {
    val projectName = ":extensions:individual:$lang:$name"
    include(projectName)
    project(projectName).projectDir = File("src/${lang}/${name}")
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
