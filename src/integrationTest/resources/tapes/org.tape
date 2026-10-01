# Demo "org" (spec section 13.3). Key moments: Screenshot lines; narration: # say: lines.
Set Shell "bash"
Set FontFamily "JetBrains Mono"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set Theme "Catppuccin Mocha"
Set WaitTimeout 30s
Set TypingSpeed 40ms

Hide
Type "export PS1='$ '; clear"
Enter
Sleep 500ms
Show

# say: Give scan an organization instead of a repository. Only the repositories with skill files are cloned.
Type "skill-atlas scan github.com/acme 2>/dev/null | grep -E '^(Repository|Searched|Scanned)'"
Enter
Wait+Screen /Scanned 3 repositories/
Sleep 4500ms
Screenshot screenshots/01-searched.png
Sleep 1s

# say: Mix organizations and repositories freely. Each repository is scanned once, and the filter works across all of them.
Type "clear; skill-atlas scan github.com/acme/agent-skills github.com/acme --filter pdf 2>/dev/null | grep -E '^(Repository|Searched|Scanned)'"
Enter
Wait+Screen /Scanned 3 repositories/
Sleep 4500ms
Screenshot screenshots/02-mixed.png
Sleep 1s

# say: Commands that work on one repository say so, and point you to scan.
Type "clear; skill-atlas browse github.com/acme"
Enter
Wait+Screen /names an organization or user/
Sleep 3500ms
Screenshot screenshots/03-one-repository.png
Sleep 1s
