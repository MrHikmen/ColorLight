-- colorlight:beam - light that carries far up and down but barely sideways (a lighthouse / pillar of light).
-- Use it from a block script:  colorlight.block("minecraft:beacon", { propagation = "colorlight:beam" })
return {
    name = "Vertical beam",
    scale = 1,
    neighbors = { {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1} },
    loss = function(dx, dy, dz, opacity, decay, range)
        if dy ~= 0 then
            return decay * 0.25 * (1 + opacity)   -- vertical hops are cheap
        end
        return decay * 2.5 * (1 + opacity)        -- sideways hops are expensive
    end,
}
