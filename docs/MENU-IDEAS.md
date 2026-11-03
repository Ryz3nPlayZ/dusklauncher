# Menu ideas (not built yet)

Ideas for the in-game menus, written down November 2026. Today the title
screen has three buttons and Quit, nine unlabelled icons in the top right and
the player model with Wardrobe on the left. The world list, server list,
pause menu and loading screens are still vanilla, only reskinned.

Suggested order: 1, 2 and 5 first (they save clicks every session), then 3
and 4. 6 to 8 are looks only.

1. **Continue button.** Above Singleplayer, one button that rejoins whatever
   was played last: world or server name, its icon, and "2 hours ago" or
   "14 online". Needs DuskConfig to remember the last world and last server.

2. **Recent and pinned servers on the title screen.** A short row of 3–4
   small cards under the buttons with each server's icon, ping and player
   count. Pinned servers always show. The vanilla server list stays for the
   rest.

3. **Friends online, with a join button.** A small stack of faces for
   friends who are online now, from `SocialNotifier.knownFriends()`.
   `playing()` already says which server each one is on, so clicking a face
   can offer "Join them on <server>".

4. **Search everything.** Ctrl+K, or just typing on the title screen, opens
   one search box over worlds, servers, settings and keybinds. Enter goes
   there. `KeybindSearch` is a starting point. Also fixes the nine icons that
   only explain themselves on hover.

5. **Pause menu with quick toggles.** Server name, ping and time played;
   one-click toggles for minimap, fullbright, performance overlay and voice
   mute; a friends button for invites.

6. **Connecting and loading screens.** Server icon and message of the day,
   the real steps as they happen ("Logging in", "Loading terrain"), and the
   title screen's animated background instead of the dirt loading bar.

7. **Background that matches the moment.** A night version of the title
   scene in the evening, or a scene for the dimension last played. Only if
   the launcher already has those scene variants.

8. **Transitions.** A short slide or fade when a menu opens, so it's clear
   where you came from. Lowest priority; easy to overdo.
