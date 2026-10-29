-- Token bucket atómico. Redis ejecuta el script entero sin intercalar otros comandos, así que
-- "leer, decidir y escribir" no se puede partir en dos (DESIGN.md §5).
--
-- KEYS[1]  clave del bucket (un hash con tokens y ts)
-- ARGV[1]  capacidad máxima del bucket
-- ARGV[2]  tokens que se recargan por minuto (0 = no recarga)
-- ARGV[3]  tokens que cuesta esta solicitud
--
-- Devuelve {permitido (1 o 0), milisegundos hasta poder pagar el costo (-1 si nunca)}.
-- La hora sale de Redis (TIME), no de la instancia que llama: dos instancias con relojes
-- distintos verían recargas distintas.

local capacity = tonumber(ARGV[1])
local refillPerMs = tonumber(ARGV[2]) / 60000
local cost = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])

if tokens == nil or ts == nil then
  tokens = capacity
else
  local elapsed = math.max(0, now - ts)
  tokens = math.min(capacity, tokens + elapsed * refillPerMs)
end

local allowed = 0
local retryMs = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
elseif refillPerMs > 0 then
  retryMs = math.ceil((cost - tokens) / refillPerMs)
else
  retryMs = -1
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))

-- El bucket se borra solo cuando ya estaría lleno otra vez: no quedan claves muertas.
local ttl = 3600000
if refillPerMs > 0 then
  ttl = math.ceil(capacity / refillPerMs) + 1000
end
redis.call('PEXPIRE', KEYS[1], ttl)

return {allowed, retryMs}
