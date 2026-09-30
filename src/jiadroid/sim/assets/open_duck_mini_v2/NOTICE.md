# Open Duck Mini v2 model

The MJCF files and the STL meshes in this directory come from the Open Duck
project by Antoine Pirrone and contributors:

- Model and meshes: [Open Duck Playground](https://github.com/apirrone/Open_Duck_Playground),
  `playground/open_duck_mini_v2/xmls`, commit `b9be205ac64488c23504ca42e5ec790337adeec3`.
  The Python sources in that repository carry an Apache-2.0 header
  (Copyright 2025 DeepMind Technologies Limited, Copyright 2025 Antoine Pirrone - Steve Nguyen).
- The CAD they were exported from: [Open Duck Mini](https://github.com/apirrone/Open_Duck_Mini), Apache-2.0.

The meshes were exported with onshape-to-robot from Onshape document `64074dfcfa379b37d8a47762`.

Changes made here:

- `open_duck_mini_v2.xml`: removed the MJX-tuned `<option iterations="1" ls_iterations="5">`.
- `scene_flat_terrain.xml`: rewritten with the same floor, `home` keyframe, and a lit checker floor.

Everything else is as upstream. Thank you to the Open Duck community.
