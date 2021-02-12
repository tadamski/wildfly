package org.jboss.as.ejb3.component.singleton;


import org.jboss.as.ee.component.deployers.StartupCountdown;
import org.jboss.invocation.Interceptor;
import org.jboss.invocation.InterceptorContext;


/**
 * Interceptor decreasing value of passed CountDownLatch per invocation.
 * Is used on @Startup @Singletons' @PostConstruct methods to signal post-construct successfuly done.
 * @author Fedor Gavrilov
 */
public class StartupCountDownInterceptor implements Interceptor {
  private final StartupCountdown countdown;

  StartupCountDownInterceptor(final StartupCountdown countdown) {
    this.countdown = countdown;
  }

  @Override
  public Object processInvocation(final InterceptorContext context) throws Exception {
    System.out.println("BAJOBONGO IDZIE STARTUP COUNTDOWN INTERCEPTOR");
    new Exception().printStackTrace();
    final StartupCountdown.Frame frame = countdown.enter();
    try {
      Object proceed = context.proceed();
      countdown.countDown();
      return proceed;
    } finally {
      System.out.println("BAJOBONGO KONIEC CZEKANIA");
      StartupCountdown.restore(frame);
    }
  }
}
