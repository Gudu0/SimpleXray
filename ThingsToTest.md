## Things to test before releasing a new version.

### Startup
1. Minecraft loads without errors
2. Both keybinds appear in Controls settings (Open Config Screen, Add Looked-At Block)

### Config Screen — layout
3. Config screen opens via keybind
4. All three panels are visible and look correct (Settings, Enabled, Add Blocks)
5. Settings panel shows placeholder text when no block is selected

### Adding blocks
6. Clicking a block in Add Blocks panel adds it to Enabled list
7. Searching in Add Blocks panel filters the list correctly
8. Add Looked-At Block keybind adds the block you're looking at
9. Dragging a block from the hotbar into the screen adds it
10. Adding a block that's already in the list does nothing (no duplicate, no freeze)

### Removing blocks
11. Remove button removes the block from the Enabled list
12. Outlines for that block disappear immediately

### Color picker (Settings panel)
13. Clicking a block in the Enabled list loads its color into the Settings panel
14. Clicking the block again, or removing the selected block clears the Settings panel back to placeholder
15. R, G, B sliders update the outline color live while dragging
16. Hex field: typing a valid 6-digit hex code updates the color live
17. Hex field and sliders stay in sync with each other
18. Color change persists after leaving and rejoining the world

### Enable Mod toggle
19. Toggle off → all outlines disappear immediately
20. Toggle on → outlines reappear (cache rescans)
21. Toggle state persists after leaving and rejoining the world

### X-Ray rendering
22. Enabled blocks are outlined through walls
23. Outlines are the correct color per block
24. Outlines update when a block is placed or broken in a loaded chunk

### Persistence
25. Enabled block list is saved and reloaded after leaving and rejoining
26. Enabled block list survives a full game restart
