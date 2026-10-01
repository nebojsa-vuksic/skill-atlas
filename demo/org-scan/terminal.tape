# Terminal demo: every repository of an organization (spec sections 5.11 and 13).
Require skill-atlas
Set Shell "bash"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set TypingSpeed 45ms
Set Theme "Catppuccin Mocha"

# say: Give scan an organization instead of a repository.
Type "skill-atlas scan github.com/anthropics --filter pdf"
Enter
Sleep 4s
# say: It lists every repository, checks each tree for skill files, and clones only those.
Sleep 16s

# say: The report ends with what was searched, and what was skipped.
Type "clear; skill-atlas scan github.com/anthropics 2>/dev/null | tail -2"
Enter
Sleep 18s

# say: Mix organizations and repositories freely. Each repository is scanned once.
Type "clear; skill-atlas scan github.com/anthropics/skills github.com/anthropics 2>/dev/null | grep -E '^(Repository|Searched|Scanned)' | head -6"
Enter
Sleep 20s
