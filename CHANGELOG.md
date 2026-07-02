###### No changelog for versions before 1.1.0 <p>

### 1.1.0

#### Improvements
- **Search panel now truncates long block names** — same as the Enabled panel; hover over
a truncated name for half a second to see the full name in a tooltip.
- **Removing a block is now instant** — previously triggered a full world rescan; now just
  filters the existing cache directly, regardless of view distance.
- **Adding a block no longer freezes the game** — the chunk rescan is spread across ticks
  (nearest chunks first) so outlines appear progressively rather than causing a
  freeze at large view distances.
- **Adding a block already in the list is a no-op** — previously re-ran the save and rescan
  unnecessarily.

### 1.1.1
- **Removed debug time messages**

### 1.2.0
- **Per-block RGB color picker** — replaced the 8-color palette with full RGB sliders  
  and a hex input field in a new Settings panel. Click a block in the Enabled list to    
  edit its color
- **Enable Mod toggle** — disable X-ray rendering and scanning without losing your block list;      
  setting is saved to disk and rescans when you re-enable