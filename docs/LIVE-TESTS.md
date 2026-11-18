# Live tests (for the owner)

These tests use real accounts and services. Claude does not do them. Use a release build on the
phone. Write the results as estimates in `research/device-test.md`.

## L-1 Setup on the Pixel (GrapheneOS)

1. Install Automa. Tap Install. Note the time and if a step fails.
2. Open a shell (terminal icon). Run `uname -a`, `opencode --version`, `git --version`, `gh --version`.
3. If "Restrict dynamic code loading" is on for Automa in GrapheneOS, note what fails.
4. If "Native code debugging" is blocked: expected, the setup shows the fix and "Open App info".

## L-2 GitHub login

1. Settings → Log in to GitHub. Tap "Copy code and open GitHub". Paste the code on
   github.com/login/device and approve.
2. Expected: while you are in the browser, the notification says "Automa is working". Back in
   Automa, the sheet closes and Settings shows your login name.
3. In a shell: `gh auth status` (expected: logged in with `GH_TOKEN`), `git config --global -l`
   (expected: your name and the no-reply address), `ls /tmp` (expected: no `automa-gh-login`).

## L-3 Clone, change, push

1. Clone a private test repository from the account list.
2. Open it in opencode. Ask for a small change. Let opencode commit.
3. In the shell of the same session: `git push`. Expected: no password question.

## L-4 Release and pull request

1. `gh release create v0.0.1-test --notes test` in a test repository. Delete the release after.
2. `gh pr create --fill` from a test branch. Close the pull request after.

## L-5 AI providers

1. API keys → add `TFF_API_KEY`. Expected: "Tokens for Friends models" shows "N models". Open a new
   opencode session, run `/models`. Expected: only "Tokens for Friends" models, no `/connect`.
   Send a short prompt and note which model answered.
2. Tap reload on the API keys screen. Expected: the same count, no error.

## L-6 Background and screen off

1. Start a long opencode task. Turn the screen off for 10 minutes.
2. Expected: the task completes; the notification shows the session; "Stop all" ends it.

## L-7 Share and files

1. Share the text of a session to a notes app.
2. Files app → Automa → Home → repos: open a file, and copy a file in from Downloads.
3. Turn on the app lock. Expected: Automa is no longer in the Files app.

## L-8 Logout

1. Settings → tap your login name. Expected: logged out; a link to revoke the token.
2. Revoke the "GitHub CLI" authorisation on github.com.

## L-9 Update

1. Settings → Update. Expected: "Update the packages", then "Up to date: opencode X.Y.Z" (or a
   download of a newer opencode first).
2. In a shell: `opencode --version` (expected: the version in Settings), `apk version -l '<'`
   (expected: no lines).
3. Start an opencode session, then tap Update. Expected: the session continues.

## L-8 Claude Code and Codex

1. Settings → Coding agents → Claude Code. Note the time of the install. Then Codex.
2. Repository → Claude Code terminal button. Select the subscription login. Expected: "Open the link in the
   browser? claude.com". Tap Open, log in, copy the code, go back to Automa, paste it with the
   paste key. Ask for a small change.
3. Repository → Codex terminal button. Select "Sign in with ChatGPT". Expected: "auth.openai.com". Tap Open,
   log in. Expected: the browser shows the Codex success page and Codex continues. Ask for a small
   change and let Codex run a command.
4. Settings → Update. Expected: "Up to date: opencode …, Claude Code …, Codex …".
5. Settings → Coding agents → Remove Codex. Expected: Codex is not in the repository sheet.

## L-10 Chat (DESIGN §13)

Use a test repository. Note for each step if the card or the answer is wrong.

1. Claude Code chat (logged in, folder trusted in the terminal). Expected: no "project settings
   are off" note. Ask: "Run `ls` and then create a file test.txt". Expected: a permission card for
   the command with "Yes", "Yes, and do not ask again for: …" and "No". Tap No with the note
   "Use git ls-files". Expected: Claude reads the note. Then allow the file with the edits
   suggestion, and check that the next edit does not ask.
2. Ask Claude: "Ask me two questions with options, one with more than one answer". Expected: a
   card with both questions, check boxes for the second, and an own answer field. Answer, and check
   that Claude repeats the answers correctly.
3. Mode chip → Plan. Ask for a small plan. Expected: the plan card. Tap "No, keep planning" with a
   note, then "Yes, and accept edits". Expected: the Mode chip shows "Accept edits".
4. During a long answer, tap Stop. Expected: "Stopped." and the composer comes back. Change the
   model and the effort with the chips, and send a short prompt.
5. Tap the terminal button in the chat. Expected: the Claude TUI opens the same conversation.
6. Codex chat in a new folder. Expected: the trust card first. Mode "Ask before changes": ask for
   a command and a file change. Expected: the command card (in the order of the TUI) and the file
   card with the diff. Then Mode → Plan: ask for a plan of a feature. Expected: a question card
   (one choice and an own answer), then the plan card; "Yes, implement this plan" starts the work.
7. opencode chat with `TFF_API_KEY`. Ask: "read the .env file" in a repository with a `.env`.
   Expected: a card with "Allow once", "Always allow" and "Reject".
8. Leave the chat screen during a turn and come back. Turn the screen off for 5 minutes. Expected:
   the turn continues; the notification counts the chat; "Stop all" ends it.

