package skillatlas.it.demo

import skillatlas.it.Sandbox
import skillatlas.it.StubGitHubApi

/**
 * The repositories every demo test uses (spec section 13.1): realistic content under neutral
 * names, so recordings look like real use while every run sees exactly the same data.
 *
 * - `acme/agent-skills`: ordinary skills, like a public skills collection
 * - `acme/workbench`: every skill in `.agents` and `.claude`, some also shipped in a plugin
 * - `acme/agent-framework`: two skills, plus test fixtures that are not skills
 *
 * `acme` is also an organization (spec section 5.12) that lists all three, plus a fork, an
 * archived repository and a website without skills.
 */
object DemoFixtures {
    const val SKILLS = "acme/agent-skills"
    const val WORKBENCH = "acme/workbench"
    const val FRAMEWORK = "acme/agent-framework"
    const val OWNER = "acme"

    fun install(sandbox: Sandbox) {
        val repositories = listOf(
            Triple(SKILLS, "Agent skills for documents, design and testing", agentSkills()),
            Triple(WORKBENCH, "A language workbench and its agent skills", workbench()),
            Triple(FRAMEWORK, "A framework for building AI agents on the JVM", framework()),
        )
        for ((name, description, files) in repositories) {
            sandbox.api.repository(name, description)
            sandbox.createRepository(name, files)
            sandbox.api.tree(name, files.keys)
        }
        val listed = repositories.map { (name, description) -> StubGitHubApi.Listed(name.substringAfter('/'), description) } + listOf(
            StubGitHubApi.Listed("agent-skills-fork", "A fork of the agent skills", fork = true),
            StubGitHubApi.Listed("legacy-tools", "Tools from before the framework", archived = true),
            StubGitHubApi.Listed("website", "The project website"),
        )
        sandbox.api.owner(OWNER, listed.sortedBy { it.name.lowercase() })
        sandbox.api.tree("$OWNER/website", listOf("README.md", "index.html"))
    }

    // Quoted, because descriptions contain ": ", which YAML would otherwise read as a new key.
    private fun skill(name: String, description: String, body: String) =
        "---\nname: $name\ndescription: \"${description.replace("\"", "\\\"")}\"\n---\n\n${body.trimIndent()}\n"

    private fun agentSkills() = mapOf(
        "skills/pdf-toolkit/SKILL.md" to skill(
            "pdf-toolkit",
            "Read, merge, split and fill PDF files, and extract their text and tables. Use whenever a task involves a .pdf file.",
            """
            # PDF toolkit

            Work with PDF files from the command line.

            ## Common tasks

            | Task | Tool |
            |------|------|
            | Extract text | `pdftotext -layout in.pdf out.txt` |
            | Merge files | `qpdf --empty --pages a.pdf b.pdf -- merged.pdf` |
            | Fill a form | `pdftk form.pdf fill_form data.fdf output filled.pdf` |

            Always check extracted tables against the original page before relying on them.
            """,
        ),
        "skills/docx-editor/SKILL.md" to skill(
            "docx-editor",
            "Create and edit Word documents (.docx): headings, tables, tracked changes and comments.",
            """
            # Word documents

            1. Unzip the document to reach `word/document.xml`.
            2. Edit the XML, keeping every run's formatting.
            3. Zip it again and open it to check the result.

            Convert to PDF with `soffice --headless --convert-to pdf` when a PDF is needed.
            """,
        ),
        "skills/slide-deck/SKILL.md" to skill(
            "slide-deck",
            "Build and edit PowerPoint decks (.pptx) with layouts, speaker notes and charts.",
            """
            # Slide decks

            - Start from the template's layouts, never from a blank slide.
            - Keep one idea per slide, and put the details in the speaker notes.
            - Export a PDF copy for readers without PowerPoint.
            """,
        ),
        "skills/spreadsheet/SKILL.md" to skill(
            "spreadsheet",
            "Work with Excel spreadsheets (.xlsx): formulas, charts, pivot tables and data cleanup.",
            """
            # Spreadsheets

            Prefer formulas over pasted values, so the sheet stays correct when data changes.
            Clean data first: trim spaces, fix dates, and remove duplicate rows.
            """,
        ),
        "skills/brand-guide/SKILL.md" to skill(
            "brand-guide",
            "Apply the company's brand colors, typography and tone of voice to any document or slide.",
            """
            # Brand guide

            | Element | Value |
            |---------|-------|
            | Primary color | `#0A7F99` |
            | Headings | Inter SemiBold |
            | Tone | Clear, warm and direct |
            """,
        ),
        "skills/canvas-art/SKILL.md" to skill(
            "canvas-art",
            "Create posters and visual art as PNG or PDF files, from a short design philosophy you write first.",
            """
            # Canvas art

            Write the design philosophy in a few sentences, then express it visually.
            Export the result as a PNG for screens and a PDF for print.
            """,
        ),
        "skills/webapp-testing/SKILL.md" to skill(
            "webapp-testing",
            "Test local web applications with Playwright: open pages, click through flows and take screenshots.",
            """
            # Web app testing

            ```python
            page.goto("http://localhost:3000")
            page.get_by_role("button", name="Sign in").click()
            page.screenshot(path="signed-in.png")
            ```
            """,
        ),
        "skills/skill-writer/SKILL.md" to skill(
            "skill-writer",
            "Write new agent skills and improve existing ones, with small evaluations to measure them.",
            """
            # Writing skills

            A good skill says when to use it in its description, and keeps its body short.
            Measure it with a few realistic prompts before and after every change.
            """,
        ),
    )

    private fun workbench(): Map<String, String> {
        val generator = skill(
            "generator",
            "Use when defining or changing model generators: templates, mapping rules and generation plans.",
            """
            # Generators

            A generator turns a model into code, one mapping rule at a time.

            ## Checklist

            - Put each rule in the mapping configuration it belongs to.
            - Give the generation plan an explicit order when generators depend on each other.
            - Regenerate the sample project and diff the output.
            """,
        )
        val editor = skill(
            "editor",
            "Use when building projectional editors: cells, layouts, actions and keymaps for a language.",
            """
            # Editors

            Build the editor from cells: constants, properties, references and collections.
            Add actions and keymaps last, once the layout reads well.
            """,
        )
        val typesystem = skill(
            "typesystem",
            "Use when defining inference rules, checking rules, subtyping rules and quick fixes for a language, " +
                "or when a WhenConcrete test statement fails to check them.",
            """
            # Type system

            Inference rules compute types; checking rules report errors; quick fixes repair them.
            """,
        )
        val tests = skill(
            "language-tests",
            "Use when writing or fixing tests for languages: node tests, editor tests and type checks, run from the test module.",
            """
            # Language tests

            Run every test from the test module, and keep one scenario per test node.
            """,
        )
        val commits = skill(
            "commits",
            "How to write commit messages and split changes in this repository.",
            """
            # Commits

            Write the subject in the imperative mood, and explain why in the body.
            """,
        )
        val files = mutableMapOf<String, String>()
        for ((name, content) in listOf("generator" to generator, "editor" to editor, "typesystem" to typesystem, "language-tests" to tests, "commits" to commits)) {
            files[".agents/skills/$name/SKILL.md"] = content
            files[".claude/skills/$name/SKILL.md"] = content
        }
        // Shipped inside the product's assistant plugin, byte for byte the same.
        files["plugins/assistant/resources/skills/generator/SKILL.md"] = generator
        files["plugins/assistant/resources/skills/editor/SKILL.md"] = editor
        files["plugins/assistant/resources/skills/typesystem/SKILL.md"] = typesystem
        return files
    }

    private fun framework() = mapOf(
        ".claude/skills/split-platform-code/SKILL.md" to skill(
            "split-platform-code",
            "Splits classes in the common source set into shared and platform code when a feature needs JVM-only APIs, like PDF or document parsing.",
            """
            # Splitting platform code

            Keep the interface in `commonMain`, and move the JVM implementation to `jvmMain`.
            Document parsing, for example of PDF files, usually needs the JVM side.
            """,
        ),
        ".claude/skills/java-snippets/SKILL.md" to skill(
            "java-snippets",
            "Adds Java examples next to the Kotlin ones in the documentation, so Java users can copy them.",
            """
            # Java snippets

            Every Kotlin example in the docs gets a Java tab with the same behavior.
            """,
        ),
        "integration-tests/src/jvmTest/resources/skills/weather-tool/SKILL.md" to skill(
            "weather-tool",
            "Test fixture: an agent tool that reports the weather.",
            "# Weather tool\n\nUsed by the integration tests only.",
        ),
        "integration-tests/src/jvmTest/resources/skills/calculator-tool/SKILL.md" to skill(
            "calculator-tool",
            "Test fixture: an agent tool that evaluates arithmetic.",
            "# Calculator tool\n\nUsed by the integration tests only.",
        ),
    )
}
