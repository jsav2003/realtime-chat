-- Para cada usuario (una clave presence:{userId} por KEYS[i]) dice si tiene alguna conexión viva.
--
-- Primero borra las conexiones vencidas (puntaje <= ahora) y después cuenta las que quedan. Lo
-- vencido se limpia aquí, al consultar, así que no hace falta ningún barrido aparte.
--
-- Devuelve una lista con 1 (en línea) o 0 por cada clave, en el mismo orden.
-- Pide varias claves a la vez: sirve con un Redis único, no con un cluster.

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local result = {}
for i, key in ipairs(KEYS) do
  redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
  if redis.call('ZCARD', key) > 0 then
    result[i] = 1
  else
    result[i] = 0
  end
end
return result
