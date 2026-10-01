-- colorlight:smooth  -  circle (круг)
--
-- Light hops between all 26 surrounding blocks (faces, edges and corners) and loses an amount proportional to the real
-- distance travelled, so the reach is a Euclidean distance: a circle from above, a sphere in 3D. Costs roughly four
-- times the work of grid.
--
--          . 3 3 3 .
--        3 3 2 2 2 3 3
--        3 2 1 1 1 2 3      a diagonal costs about 1.4 steps, a corner about 1.7;
--        3 2 1 0 1 2 3      the running value is kept in 1/8 units and rounded
--        3 2 1 1 1 2 3      only when stored, so rounding errors do not pile up
--        3 3 2 2 2 3 3
--          . 3 3 3 .
--
-- This is also the method moving (entity) lights use by default (setting "dynamic_propagation").

return {
    name = "Smooth (circle)",

    scale = 8,            -- working values in 1/8 light units
    preserve_hue = true,
    max_opacity = 15,

    neighbors = colorlight.full,

    loss = function(dx, dy, dz, opacity, decay, range)
        local distance = math.sqrt(dx * dx + dy * dy + dz * dz)
        return distance * (1 + opacity) * decay
    end,
}
