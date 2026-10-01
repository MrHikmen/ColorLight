-- colorlight:cross - a fully scripted method: hops between the 6 face neighbours and fades 1.5x faster than grid.
-- Shows the whole propagate(field, queue, ctx) API; change the maths to get any shape you can compute.
-- Slower than table-driven methods (it runs in the Lua interpreter) - fine for a few special lights.
return {
    name = "Cross (scripted)",
    propagate = function(field, queue, ctx)
        -- ctx.decay = light units lost per block, ctx.range = light range in blocks, ctx.max_opacity
        while true do
            local x, y, z = queue.poll()
            if not x then break end
            local r, g, b = field.get(x, y, z)
            local peak = math.max(r, g, b)
            if peak > 0 then
                for _, d in ipairs(colorlight.faces) do
                    local nx, ny, nz = x + d[1], y + d[2], z + d[3]
                    local opacity = field.opacity(nx, ny, nz)
                    if opacity < ctx.max_opacity then
                        local newPeak = peak - ctx.decay * 1.5 * (1 + opacity)
                        if newPeak > 0 then
                            local k = newPeak / peak
                            local nr, ng, nb = math.floor(r * k), math.floor(g * k), math.floor(b * k)
                            local cr, cg, cb = field.get(nx, ny, nz)
                            if nr > cr or ng > cg or nb > cb then
                                field.set(nx, ny, nz, math.max(nr, cr), math.max(ng, cg), math.max(nb, cb))
                                queue.add(nx, ny, nz)
                            end
                        end
                    end
                end
            end
        end
    end,
}
