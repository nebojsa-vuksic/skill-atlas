# Demo "shell" (spec section 13.3). Key moments: Screenshot lines; narration: # say: lines.
Set Shell "bash"
Set FontFamily "JetBrains Mono"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set Theme "Catppuccin Mocha"
Set WaitTimeout 30s
Set TypingSpeed 70ms

Hide
Type "export PS1='$ '; clear"
Enter
Sleep 500ms
Show

# say: Or just type skill atlas. You get an interactive shell.
Type "skill-atlas"
Enter
Wait+Screen /for commands/
Sleep 2500ms
Screenshot screenshots/01-prompt.png
Sleep 1s

# say: Type a slash, and every command shows up.
Type "/"
Wait+Screen /Scan a GitHub repository/
Sleep 2500ms
Screenshot screenshots/02-palette.png
Sleep 1s

# say: Keep typing to narrow it down.
Type "sc"
Sleep 2500ms
Screenshot screenshots/03-narrowed.png
Sleep 1s

# say: Tab completes the command. Scan a repository, and it becomes the one you're working on.
Tab
Sleep 500ms
Type "github.com/acme/agent-skills"
Enter
Wait+Screen /8 skills found/
Sleep 3s
Screenshot screenshots/04-scanned.png
Sleep 1s

# say: Tab completes skill names, too.
Type "/skill pdf"
Sleep 700ms
# VHS can't read the shell's live input line, so no Wait here. Completion is in-process and instant.
Tab
Sleep 2s
Screenshot screenshots/05-skill-completion.png
Sleep 1s
Enter
Sleep 2s
Type "/quit"
Enter
Sleep 1s
