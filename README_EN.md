# Create: Green Print

Create: Green Print is a Create addon currently supporting Minecraft 1.20.1 (Forge) and 1.21.1 (NeoForge). It extends Create's blueprint workflow with an expandable recipe graph and recursive crafting support.

A Green Print can organize multiple recipes into a crafting tree (stored internally as a connected recipe graph). When a target recipe requires an item produced by another Green Print node, the mod searches for and attempts to craft the required prerequisites first.

## Features

- Craft Green Prints from a Create blueprint and green dye.
- Store multiple recipe nodes in one Green Print without being limited to a fixed 3x3 layout.
- Connect edge-adjacent Green Prints with matching orientations into one shared recipe component.
- Preserve Minecraft `Ingredient` and tag-based recipe alternatives.
- Check the player's inventory and recursively search connected Green Print recipes for missing ingredients.
- Support output counts, actual recipe matching, tool durability, and container return items.
- Use transactional crafting: failed searches do not partially consume the player's inventory.
- Reuse Create's ingredient overlay and distinguish between available, recursively craftable, and unavailable materials.

## Usage

1. Craft a Green Print using one Create blueprint and one green dye.
2. Place the Green Print on a valid surface and use a Create wrench to open its recipe editor.
3. Configure recipes in different nodes. Adjacent nodes become part of the same crafting tree.
4. Place multiple Green Prints edge to edge with the same orientation when recipes need to be shared across prints.
5. Click a recipe node with an output to attempt crafting. Holding Shift repeats the operation until materials run out or 64 crafting passes have completed.

## Recipe Search

For each crafting attempt, the mod collects all recipes from the current Green Print and its physically connected Green Prints. It builds an index of candidate recipes by output item and resolves each requested ingredient in the following order:

1. Reserve matching items from the player's inventory.
2. Use virtual outputs already produced by the current search branch.
3. If the ingredient is still missing, select a connected Green Print recipe that produces a matching `Ingredient` and resolve its inputs recursively.
4. Backtrack between candidate recipes until a branch can satisfy the complete crafting grid.
5. Commit the transaction only after all inputs and prerequisites have been resolved successfully, then return the final output to the player's inventory.

The search tracks active recipes and limits recursion depth to 64 to prevent recipe cycles. Recipe indexes and discovered recipes are cached briefly and invalidated automatically when Green Prints are edited or their connections change.

## Supported Versions

| Minecraft | Loader | Create |
| --- | --- | --- |
| 1.20.1 | Forge 47.4.10 | 6.0.8-291 |
| 1.21.1 | NeoForge 21.1.244 | 6.0.11-295 |

## Development Environment

| Component | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.10 |
| Create | 6.0.8-291 |
| Java | 17 |

Build the project:

```powershell
.\gradlew.bat build
```

Compile Java sources only:

```powershell
.\gradlew.bat compileJava
```
