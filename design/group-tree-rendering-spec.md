# Group Decision-Tree Rendering Specification

**Author:** architect-worker
**Date:** 2026-05-15
**Sources:** 需求擴增/樹的輸出視覺化.jpg (decoded by orchestrator); MISSION.md §4.M4; PROJECT_PLAN.md §6
**Status:** draft — to be reviewed by frontend impl and M4 GroupLayoutEngine impl

## 1. Purpose

This document formalises the visual conventions of the Group group rule engine's standard decision-tree UI, as decoded from the sample screenshot `需求擴增/樹的輸出視覺化.jpg`. Its purpose is to give M4 (`GroupLayoutEngine`) and the M7 Dashboard "Engine Execution" tab a single source of truth so that the `position.x/y` values produced by the layout engine, when rendered by the dashboard, yield the intended Group-style visual. This spec covers node shape and colour, edge label formatting, layout direction, background chrome, and hit-path highlighting. It explicitly does **not** cover the layout algorithm itself (that is M4's `GroupLayoutEngine` design decision), the choice of JS tree library on the frontend (the dashboard team chooses, subject to the rules here), animation, mobile/touch interaction, or any official Group brand stylesheet that may later supersede the proposed colour values.

## 2. Scope: who consumes this spec

- **M4 `GroupLayoutEngine`** — produces `position.x/y` integer pixel values on each Group `TreeNode` such that, when those positions are fed to a renderer following the rules below, the resulting layout matches the intended visual.
- **M7 dashboard "Engine Execution" tab** — renders the Group JSON tree (the freshly exported one from PROJECT_PLAN.md §6 Scene 3) using a JS tree library (react-d3-tree / reactflow / dagre — the dashboard team chooses, but must implement the rules below).
- **Future engine adapters** (Drools, IBM ODM, FICO) — may reuse this spec when their target engine has a similar hierarchical tree UI. Adapter authors are free to override colour/abbreviation choices for their respective engine families; the structural rules (top-down, branch vs leaf differentiation, edge labels carry the operator) should be preserved as the family default.

## 3. Node visual rules

### 3.1 Branch nodes

A branch node is a `RuleEnvelope.TreeNode` that has a non-null `condition` (or non-empty `branches[]`) and is not itself a leaf. It corresponds to an inner node in the screenshot (`產地別`, `存款`, `塞車`, `重視`).

- Shape: rounded rectangle, ~110px wide × ~40px tall (**proposed**; treat as guidance — final dimensions may shrink to fit longer Chinese field names)
- Corner radius: ~6px (**proposed**)
- Background: pale grey, `#F5F5F5` (**proposed** — sampled approximately from the screenshot)
- Border: 1px solid, slightly darker than background, `#E0E0E0` (**proposed**)
- Text: the field's Chinese display label, looked up from the corresponding `FieldDef` (prefer `FieldDef.name`; if the input came from a Group JSON import, `FieldDef.fieldCode` is the back-up). Font size ~14px, centred horizontally and vertically. Text colour: dark grey `#333333` (**proposed**)
- Visual weight: deliberately quiet. Branch nodes carry no semantic emphasis — they are scaffolding for the decisions made on their outgoing edges.

### 3.2 Leaf nodes

A leaf node is a `RuleEnvelope.TreeNode` that has a non-null `results[]` (and typically a null `condition`). In the screenshot, leaves are `Rolls-Royce`, `Benz`, `Lexus`, `多存一點吧(None)`, `馬自達(MAZDA)`, `Ford`, `Toyota`.

- Shape: rounded rectangle, same approximate dimensions as branch nodes (~110px × ~40px, ~6px corner radius)
- Background: white `#FFFFFF`
- Border: **2px solid blue, `#1976D2`** (**proposed** — sampled from the screenshot; treat hex as approximate until Group confirms the canonical brand-blue)
- Text content rule: `<output-field-display-name>: <value>(<code>)` where applicable. Concretely:
  - If the leaf's `results[0]` has an output field whose `FieldDef.typeRef` is `ENUM` and the value has both a `code` and a display label, render the value part as `<chinese-label>(<code>)` — e.g. `車種: Rolls-Royce(Rolls-Royce)` or `車種: 馬自達(MAZDA)`.
  - If there is no separate display label (the value is a plain string/number), render the value part as the value itself — e.g. `車種: Ford`.
  - For null/empty/no-match leaves, use a business-readable phrase plus a trailing `(None)` suffix — e.g. `多存一點吧(None)`. The phrase comes from the result's value text; if the value is literally null/empty, the renderer falls back to `(無對應)` (**inferred** convention).
- Text colour: dark grey `#333333` (**proposed**), same as branch nodes; the blue is reserved for the border.
- Truncation: see §3.3.

### 3.3 Long-value truncation rule

When the rendered text width of a node label exceeds ~120px (i.e. would overflow the node box), the value portion (everything after the colon) is truncated with a trailing `...`. The output-field-display-name portion (before the colon) is never truncated — it is short by construction (~2–4 Chinese characters or a short ASCII identifier). Truncation operates on grapheme clusters so that both CJK and ASCII strings truncate without breaking a half character. The full untruncated text is shown on hover via the standard browser `title` attribute (**proposed** — a richer tooltip with the full `results[]` payload is a desirable enhancement but not required for v1). The threshold of ~120px is **proposed** and assumes a 14px font; the frontend may adjust it to match the chosen library's measurement primitives, provided the visual goal (one-line label, no overflow) is met.

## 4. Edge label rules

Edges in the tree connect a parent branch node to each child via a `RuleEnvelope.Branch`. The edge label is derived from `Branch.condition` (the condition that, when true on the parent's `condition.field`, selects this child).

### 4.1 Operator abbreviation table

| `RuleEnvelope.Condition.operator` | Edge label format | Example | Source |
|---|---|---|---|
| `equals` | `= <value>` | `= 進口` | observed in screenshot |
| `notEquals` | `!= <value>` | `!= 進口` | **inferred** (complement of `equals`) |
| `greaterThan` | `> <value>` | `> 1000` | observed in screenshot |
| `greaterThanOrEqual` | `>= <value>` | `>= 18` | **inferred** |
| `lessThan` | `< <value>` | `< 200` | observed in screenshot |
| `lessThanOrEqual` | `<= <value>` | `<= 65` | **inferred** |
| `between` (closed interval `[min, max]`) | `[] <min>..<max>` | `[] 500..1000` | observed in screenshot |
| `in` (set membership) | `in [a, b, c]` | `in [TW, HK, JP]` | **inferred** — needs Group confirmation; alternate proposal: `∈ {a, b, c}` if the rendering font supports the glyph |
| `notIn` | `not in [a, b, c]` | `not in [TW]` | **inferred** — alternate: `∉ {a, b, c}` |
| `isNull` | `is null` | `is null` | **inferred** |
| `isNotNull` | `is not null` | `is not null` | **inferred** |
| `anything` | `*` | `*` | **inferred** — alternate: omit the edge label entirely (the "catch-all" branch is often visually distinguishable by being the rightmost / last branch) |

Edge label visual style (all operators):

- Font size: ~12px (one step smaller than node text) (**proposed**)
- Colour: medium grey `#666666` (**proposed**)
- Placement: along the edge, slightly offset above the edge line, near the midpoint; the renderer chooses the precise offset to avoid overlap with the line itself
- Background: optional white halo (`~2px` white outline behind the text) to maintain legibility when the edge crosses the dotted grid (**proposed**)

### 4.2 ENUM value rendering on edge labels

When the condition's `value` is an ENUM code (i.e. `Condition.field` resolves to a `FieldDef` whose `typeRef` is `ENUM`), the rendered value on the edge label follows the same `<chinese-label>(<code>)` convention as leaves — e.g. an edge `産地別 equals "IMPORT"` renders as `= 進口(IMPORT)` if both label and code are available, or simply `= 進口` if the code equals the label (as in the screenshot). The edge label is truncated with `...` when its rendered width exceeds ~80px (**proposed** — smaller than the leaf threshold because edges have less horizontal real estate).

### 4.3 Value reference rendering

When the condition uses `Condition.valueRef` instead of a literal `value` (the v3.12 cross-field or relative-date case described in `RuleEnvelope.Condition`), the edge label renders the reference rather than dereferencing it at render time:

- Cross-field reference (e.g. `valueRef = "birthday"`): render as `= [<field-chinese-display-name>]` — e.g. `= [被保人生日]`. The square brackets are intentional and match the convention already used in sample B's NL spec (`不可小於 [被保人生日]`).
- Relative date (e.g. `valueRef = "$today+30d"`): render the expression literally — e.g. `> $today+30d`. The renderer does not evaluate or pretty-print the expression at design time. (**inferred** convention — Group's own UI may localise this as `> 今天+30天`; flag for confirmation.)
- If both `value` and `valueRef` are inadvertently present, the renderer prefers `valueRef` and flags the condition for the validator (this is a normalisation invariant, not a rendering concern, but called out here for awareness).

## 5. Layout rules

- **Direction:** top-down. Root at the top of the canvas, leaves at the bottom. Reading order is left-to-right at each level. (Observed in screenshot.)
- **Algorithm class:** hierarchical / Sugiyama-style. The M4 `GroupLayoutEngine` is **not** required to use a heavyweight library (e.g. dagre is overkill for the tree sizes seen so far — typically < 30 nodes). A simple recursive algorithm that:
  1. Computes the subtree width of each node bottom-up (a leaf has width = node width + horizontal sibling gap; a branch has width = sum of child widths)
  2. Places each parent horizontally centred over its children's bounding box
  3. Assigns y by depth × level spacing
  
  is sufficient and is what the spec assumes consumers will produce. The choice is M4's; this spec only constrains the inputs and outputs of layout, not its internals.
- **Spacing (proposed):**
  - Vertical between levels: ~80px (centre-to-centre)
  - Horizontal between siblings: ~40px minimum gap between adjacent node edges
  - Outer margin: ~24px between the outermost nodes and the canvas edge
- **Coordinate system:** top-left origin, x increasing right, y increasing down. `position.x` and `position.y` are integer pixel values stored on each Group `TreeNode` (the importer reads them; the exporter writes them).
- **`Position` semantics — open question:** per PROJECT_PLAN.md §9.1, it is **open** whether the real Group engine attributes meaning to `position.x/y` beyond presentation. The M4 `GroupLayoutEngine` produces purely cosmetic values; this assumption is flagged here and in §9 for human confirmation later. If the engine *does* attribute meaning (e.g. ordering of `MULTI` hits, hint to a downstream serialiser), the layout engine will need to preserve a stable mapping rather than re-laying-out from scratch on every export.

## 6. Background and chrome

- **Canvas background:** light dotted grid. Suggested pitch: 16px (dot centre to dot centre). Dot colour `#E8E8E8` (**proposed**); dot radius ~1px. Canvas base colour white. The grid is purely cosmetic — it gives the user a visual anchor when panning and is consistent with the screenshot.
- **Zoom controls:** vertically stacked, anchored to the bottom-left corner of the canvas, ~16px inset from the canvas edges. From top to bottom: `+` (zoom in), `−` (zoom out), `[ ]` (reset / fit-to-screen), 🔒 lock icon (lock pan; clicking toggles between panning enabled and disabled). Each button is ~32px square, white background, 1px grey border, dark-grey icon. (**proposed** dimensions and icon set; observed presence and order from screenshot.)
- **Pan:** drag-to-pan on the canvas background. Pointer cursor changes to a grab/grabbing cursor on hover/active.
- **Zoom:** mouse wheel zooms in/out centred on the cursor position. Pinch-zoom on touch devices is out of scope (§8).
- **Initial fit:** on first render of a tree, the canvas zoom is set such that the entire tree is visible with ~24px margin on all sides. This is the same behaviour as the `[ ]` reset button.

## 7. Hit-path highlighting (PROJECT_PLAN §6 Scene 4)

When the M7 demo runs an input through `MockGroupEngine` and the engine returns a hit (a leaf `TreeNode` and the path from root to that leaf), the renderer applies the following emphasis:

- **Hit leaf:** the blue border thickens from 2px → ~3px and changes to a brighter / darker blue `#0D47A1` (**proposed** — same Material blue family, deeper shade); the leaf's background remains white so the text stays legible.
- **Hit path edges:** the edges from root to hit leaf are emphasised — line width 2px (up from default 1px), colour `#0D47A1` matching the hit-leaf border. The edge labels on the hit path retain their text but their colour shifts from `#666666` to `#0D47A1` for consistency.
- **Branch nodes on the hit path:** background optionally shifts to a very pale blue `#E3F2FD` (**proposed**) to subtly mark them as part of the traversal. This is a refinement; if the frontend chooses to keep them plain grey, that is acceptable.
- **Non-hit subtrees:** every node and edge not on the hit path fades to ~50% opacity. This is the strongest cue and is the primary visual signal that "this branch was not taken".
- **Hit badge:** a small green dot (~8px diameter, colour `#43A047` **proposed**) renders at the top-right corner of the hit leaf, with the matched `ruleId` (e.g. `R12`) shown as a small label below the leaf box (font ~10px, colour `#43A047`). The badge is intentionally compact — the user's eye should land on the leaf first, then notice the badge.
- **Multi-hit (MULTI hitPolicy):** if the engine returns multiple hits, every hit leaf is highlighted as above. The edges are coloured by union of all paths; nodes on more than one path stay full-opacity. (**inferred** — see §9.)

## 8. What this spec does NOT cover

- The layout algorithm itself (M4 `GroupLayoutEngine` decides — this spec only constrains its outputs)
- The choice of JS tree library (frontend impl decides — react-d3-tree, reactflow, dagre, custom canvas, etc. are all permissible provided the rules above are met)
- Animation (transitions on hit highlighting, layout changes, etc. are out of scope; future enhancement)
- Touch gestures and mobile-specific layout (out of scope for v1)
- Group group's official brand colours — every hex value in this document is **proposed** based on screenshot sampling and Material Design adjacency. A future revision will reconcile against the canonical Group brand stylesheet once obtained.
- Accessibility (keyboard navigation, screen-reader semantics for tree structure) — out of scope for v1 but flagged as a known gap.

## 9. Open questions (file these in QUESTIONS.md as non-blocking)

- **Q-tree-1:** What is Group's exact brand-blue hex code? The screenshot suggests something close to `#1976D2`, but a Group stylesheet or design-system document would let us drop the "**proposed**" qualifier on §3.2 / §4.1 / §7.
- **Q-tree-2:** Are the proposed abbreviations for `in` / `notIn` / `isNull` / `isNotNull` / `anything` consistent with Group's existing UI? The reference screenshot only contains `equals`, `greaterThan`, `lessThan`, and `between`, so all other operators are extrapolated.
- **Q-tree-3:** Does the Group engine attribute semantic meaning to `position.x/y`, or are they purely cosmetic? (Duplicate of PROJECT_PLAN.md §9.1 — re-raised here because it directly affects whether `GroupLayoutEngine` may freely re-lay-out on each export.)
- **Q-tree-4:** How is `MULTI` hitPolicy visualised in Group's UI? The screenshot depicts a `FIRST`-style tree (single path). §7's multi-hit description is currently inferred.
- **Q-tree-5:** Is there a Group convention for displaying `valueRef` references? The bracket notation `[被保人生日]` is borrowed from sample B's NL spec, which is suggestive but not authoritative.
- **Q-tree-6:** For the `(None)` suffix on null/empty leaves (e.g. `多存一點吧(None)`) — is `None` the Group convention, or should this be `(無)` / `(其他)`? The screenshot shows `(None)` literally, so this is observed, but it may be context-specific.

## 10. Manifest of downstream files that consume this spec

| File | Mission | What it consumes |
|---|---|---|
| `service/adapter/group/GroupLayoutEngine.java` | M4 | §5 layout rules → `position.x/y` integer values on each Group `TreeNode` |
| `service/adapter/group/GroupJsonExporter.java` | M4 | §5 indirectly (invokes `GroupLayoutEngine` to populate positions before export) |
| `frontend-src/.../components/DecisionTreeView.tsx` (or its successor in the M7 dashboard) | M7 Engine Execution tab | §3 node visual rules, §4 edge label rules, §6 canvas + zoom chrome, §7 hit-path highlighting |
| `frontend-src/.../api/engineExecutionClient.ts` (or equivalent) | M7 Engine Execution tab | Indirect — the API client passes the engine's hit result to the tree view, which renders it per §7 |
| `service/engine/MockGroupEngine.java` | M6 | §7 indirectly — the engine's `ExecutionResult` shape must carry enough information (hit leaf id, ordered list of node ids on the hit path, hit `ruleId`) for the renderer to apply §7's emphasis rules |
| (future) `service/adapter/drools/DroolsLayoutEngine.java` etc. | post-MVP | §3–§7 as a default family style; engine-specific overrides allowed |

*End of spec.*
