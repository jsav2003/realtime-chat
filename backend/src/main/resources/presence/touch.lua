-- Marca una conexión como viva hasta dentro de ttl milisegundos.
--
-- KEYS[1]  presence:{userId}, un ZSET con un miembro por conexión (instancia:sesión)
-- ARGV[1]  id de la conexión
-- ARGV[2]  ttl en milisegundos
--
-- El puntaje de cada miembro es la hora (de Redis, no de la instancia) en que su conexión deja de
-- contar como viva. La clave entera también vence, para que un usuario que no vuelve no deje
-- basura.

local ttl = tonumber(ARGV[2])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

redis.call('ZADD', KEYS[1], now + ttl, ARGV[1])
redis.call('PEXPIRE', KEYS[1], ttl)
return 1
