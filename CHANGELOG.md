###### No changelog for versions before 1.1.0 <p>

## 1.1.0

### Improvements
- **Search panel now truncates long block names** — same as the Enabled panel; hover over
  a truncated name for half a second to see the full name in a tooltip.
- **Removing a block is now instant** — previously triggered a full world rescan; now just
  filters the existing cache directly, regardless of view distance.
- **Adding a block no longer freezes the game** — the chunk rescan is spread across ticks
  (nearest chunks first) so outlines appear progressively rather than causing a
  freeze at large view distances.
- **Adding a block already in the list is a no-op** — previously re-ran the save and rescan
  unnecessarily.
