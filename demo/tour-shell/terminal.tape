# Tour 6 of 6: the interactive shell (spec sections 5.9 and 13.5).
Require skill-atlas
Set Shell "bash"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set TypingSpeed 70ms
Set Theme "Catppuccin Mocha"

# say: Or just type skill atlas. You get an interactive shell.
Type "skill-atlas"
Enter
Sleep 4s

# say: Type a slash, and every command shows up.
Type "/"
Sleep 3500ms

# say: Keep typing to narrow it down. Tab completes the command.
Type "sc"
Sleep 1s
Tab
Sleep 1s
Type "github.com/anthropics/skills"
Sleep 800ms

# say: Scan a repository, and it becomes the one you're working on.
Enter
Sleep 8s

# say: Filter it without ever leaving the shell.
Type "/filter pdf"
Enter
Sleep 5s

# say: Tab completes skill names, too.
Type "/skill doc"
Sleep 1s
Tab
Sleep 1500ms
Enter
Sleep 4s

# say: Log shows what you scanned. And quit, when you're done.
Type "/log"
Enter
Sleep 2500ms
Type "/quit"
Enter
Sleep 2s
