# `act_toward_goal` real-device eval (R5)

Harness: `eval/act_toward_goal_eval.py` (Python stdlib only).
Flows: `eval/flows/*.json` (6 scripted flows on Settings + Chrome).

## Run it (needs a real phone)

1. Build + install the APK, enable the Relay accessibility service.
2. In the app's Goal engine settings, pick the tier for this run.
3. Run (token never touches disk or git):
   `RELAY_URL=http://<phone-ip>:8765/mcp RELAY_TOKEN=<token> python3 eval/act_toward_goal_eval.py --device "<model>, Android <ver>" --tier heuristic`
4. Second pass with tier B (only if a Jev key is configured in settings):
   same command with `--tier jev`, then compare the `engine` fields.

Output: `eval/results/<YYYYMMDD-HHMM>.json` (raw audit trail, gitignored --
contains real UI text) + matching `.md` (the committable report).
Commit only the `.md`: `git add eval/act_toward_goal_eval.py eval/flows
eval/results/*.md`, then (this card only) `git push origin
feat/act-toward-goal`.
