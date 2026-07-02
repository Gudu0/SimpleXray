## Steps to release a new version of the mod.

### Prep
1. Remove all debug messages from the code
2. Bump mod_version in gradle.properties
3. Add changelog entry in CHANGELOG.md
4. Update CLAUDE.md if any architecture decisions or limitations changed
5. Update README.md if features changed (also update Downloads table later — see step 14)

- __I AM AT THIS STEP__

### Test and build for 1.20.1
6. Run through ThingsToTest.md fully in the dev client (./gradlew runClient)
7. Build the 1.20.1 jar
8. Test the built jar in the CurseForge pack using ThingsToTest.md (not the dev client)
9. Commit to GitHub: version bump, changelog, updated docs

### Build and test other versions
10. Build for each other supported version (1.20, 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6), fixing issues as they come up
11. Test each built jar in its CurseForge pack using ThingsToTest.md
12. Commit any fixes to GitHub

### Publish
13. Replace the old jars in builds/ with the new ones, commit to GitHub
14. Update the Downloads table in README.md with the new jar links, commit
15. Upload to CurseForge
