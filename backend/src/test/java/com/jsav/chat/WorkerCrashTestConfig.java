package com.jsav.chat;

import com.jsav.chat.WorkerCrashes.Point;
import com.jsav.chat.notification.MentionTopology;
import java.util.Arrays;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Envuelve al listener del worker con un consejo que lo hace «morir» donde {@link WorkerCrashes}
 * diga. La excepción sale por fuera del listener, así que el código del worker no la atrapa ni la
 * cuenta como un intento fallido: para RabbitMQ es lo mismo que un proceso que desaparece, el
 * mensaje queda sin confirmar y se entrega de nuevo.
 *
 * <p>Spring Boot aplica un {@code ContainerCustomizer} a todos los listeners; aquí se limita al de
 * la cola de menciones para no tocar, por ejemplo, el canal de control entre instancias.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WorkerCrashTestConfig {

  @Bean
  WorkerCrashes workerCrashes() {
    return new WorkerCrashes();
  }

  @Bean
  ContainerCustomizer<SimpleMessageListenerContainer> workerCrashAdvice(WorkerCrashes crashes) {
    return container -> {
      if (Arrays.asList(container.getQueueNames()).contains(MentionTopology.QUEUE)) {
        container.setAdviceChain(crashAdvice(crashes));
        // Si la conexión se corta, volver a consumir enseguida (por defecto espera 5 s).
        container.setRecoveryBackOff(new FixedBackOff(100, FixedBackOff.UNLIMITED_ATTEMPTS));
      }
    };
  }

  private static MethodInterceptor crashAdvice(WorkerCrashes crashes) {
    return invocation -> {
      if (crashes.shouldCrash(Point.BEFORE_PROCESSING)) {
        throw new SimulatedCrash("antes de procesar el evento");
      }
      Object result = invocation.proceed();
      if (crashes.shouldCrash(Point.AFTER_PROCESSING)) {
        throw new SimulatedCrash("después de guardar la notificación, antes de confirmar");
      }
      return result;
    };
  }
}
