-- colorlight:grid  -  diamond (ромб)
--
-- Light hops between the 6 face-adjacent blocks and loses the same amount at every hop, so the reach is counted in
-- blocks walked along the axes ("Manhattan distance"): a diamond from above, an octahedron in 3D. Cheap, and looks like
-- vanilla block light.
--
--            1
--          2 1 2
--        3 2 1 2 3        light falls by one step per block along an axis;
--      4 3 2 1 2 3 4      a diagonal costs two steps
--        3 2 1 2 3
--          2 1 2
--            1
--
-- Copy this file to your own resource pack (same path) to change how ALL grid light behaves, or save it under another
-- name (assets/colorlight/propagation/<name>.lua) to add a new method, id "colorlight:<name>".

return {
    name = "Grid (diamond)",

    -- Fixed-point precision of the running value. 1 = whole light units (fastest).
    scale = 1,

    -- true: all three colour channels fade together, so the hue stays the same to the very edge.
    -- false: each channel loses the same amount independently (weak channels vanish first, the colour drifts).
    preserve_hue = true,

    -- A block with this opacity or more stops light completely (15 = fully opaque, vanilla).
    max_opacity = 15,

    -- Which blocks light can hop to from a cell: a list of {dx, dy, dz}, each -1, 0 or 1.
    -- colorlight.faces = the 6 face neighbours, colorlight.full = all 26 surrounding blocks.
    neighbors = colorlight.faces,

    -- Light units (0..255) lost by one hop.
    --   dx, dy, dz : the hop
    --   opacity    : light dampening (0..15) of the block being entered
    --   decay      : units lost per block through a fully transparent block = 255 / range
    --   range      : the configured light range in blocks
    loss = function(dx, dy, dz, opacity, decay, range)
        return (1 + opacity) * decay
    end,
}
