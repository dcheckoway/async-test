import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class AsyncServletTest2 extends HttpServlet {
    private static final transient Logger logger = Logger.getLogger(AsyncServletTest2.class.getName());

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
            private final AtomicLong threadIdSequence = new AtomicLong(1L);
            
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "async-servlet-" + threadIdSequence.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };

    private BlockingQueue<Runnable> workQueue;
    private ThreadPoolExecutor threadPool;

    public AsyncServletTest2() {
        workQueue = new LinkedBlockingQueue<Runnable>();
        threadPool = new ThreadPoolExecutor(5, 10, 30000, TimeUnit.MILLISECONDS, workQueue, THREAD_FACTORY);
    }
    
    @Override
    public void destroy() {
        threadPool.shutdownNow();
        super.destroy();
    }
    
    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws java.io.IOException {
        final AsyncContext asyncContext = request.startAsync(request, response);

        final FutureTask futureTask = new FutureTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        logger.info("Handling request for " + request.getRequestURI() + " in async mode");
                        if (request.getRequestURI().endsWith("/redirect1")) {
                            logger.info("Redirecting using sendRedirect");
                            response.sendRedirect("http://tomcat.apache.org");
                        } else if (request.getRequestURI().endsWith("/redirect2")) {
                            logger.info("Redirecting using setStatus and setHeader");
                            response.setStatus(HttpServletResponse.SC_FOUND);
                            response.setHeader("Location", "http://tomcat.apache.org");
                        } else if (request.getRequestURI().endsWith("/error1")) {
                            logger.info("Sending error response using sendError");
                            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Sorry, that was a bad request.");
                        } else if (request.getRequestURI().endsWith("/error2")) {
                            logger.info("Sending error response using setStatus");
                            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                            response.getWriter().append("Sorry, that was a bad request.\n").flush();
                        } else {
                            logger.info("Sending a response");
                            response.getWriter().append("You requested: " + request.getRequestURI() + "\n").flush();
                        }
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Async task failed", e);
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        try {
                            response.getWriter().flush();
                        } catch (java.io.IOException e2) {
                            logger.log(Level.WARNING, "Failed to flush response writer", e2);
                        }
                    } finally {
                        asyncContext.complete();
                    }
                }
            }, null);

        asyncContext.addListener(new AsyncServletTestListener(futureTask), request, response);
        
        threadPool.execute(futureTask);
    }

    private static final class AsyncServletTestListener implements AsyncListener {
        private final FutureTask futureTask;

        public AsyncServletTestListener(FutureTask futureTask) {
            this.futureTask = futureTask;
        }

        @Override
        public void onTimeout(AsyncEvent event) throws java.io.IOException {
            Throwable thrown = event.getThrowable();
            if (thrown != null) {
                logger.log(Level.WARNING, "Async request timed out for " + ((HttpServletRequest)event.getAsyncContext().getRequest()).getRequestURI(), thrown);
            } else {
                logger.warning("Async request timed out for " + ((HttpServletRequest)event.getAsyncContext().getRequest()).getRequestURI());
            }                
            
            // Cancel the running task
            futureTask.cancel(true);
            
            // Write the timeout response
            HttpServletResponse response = (HttpServletResponse)event.getAsyncContext().getResponse();
            response.setStatus(HttpServletResponse.SC_REQUEST_TIMEOUT);
            response.getWriter().flush();
        }

        @Override
        public void onComplete(AsyncEvent event) throws java.io.IOException {
            logger.info("Async request completed");
        }

        @Override
        public void onError(AsyncEvent event) throws java.io.IOException {
            Throwable thrown = event.getThrowable();
            if (thrown != null) {
                logger.log(Level.SEVERE, "Async request error for " + ((HttpServletRequest)event.getAsyncContext().getRequest()).getRequestURI(), thrown);
            } else {
                logger.severe("Async request error for " + ((HttpServletRequest)event.getAsyncContext().getRequest()).getRequestURI());
            }

            // Cancel the running task
            futureTask.cancel(true);
            
            // Write the error response
            HttpServletResponse response = (HttpServletResponse)event.getAsyncContext().getResponse();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().flush();

            try {
                event.getAsyncContext().complete();
            } catch (IllegalStateException e) {
                // The async processor must already have completed it
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws java.io.IOException {
            logger.info("Async event started");
        }
    }
}