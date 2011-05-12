import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class AsyncServletTest extends HttpServlet {
    private static final transient Logger logger = Logger.getLogger(AsyncServletTest.class.getName());

    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws java.io.IOException {
        final AsyncContext asyncContext = request.startAsync(request, response);

        asyncContext.addListener(new AsyncServletTestListener(), request, response);
        
        asyncContext.start(new Runnable() {
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
            });
    }

    private static final class AsyncServletTestListener implements AsyncListener {
        @Override
        public void onTimeout(AsyncEvent event) throws java.io.IOException {
            Throwable thrown = event.getThrowable();
            if (thrown != null) {
                logger.log(Level.WARNING, "Async request timed out for " + ((HttpServletRequest)event.getAsyncContext().getRequest()).getRequestURI(), thrown);
            } else {
                logger.warning("Async request timed out for " + ((HttpServletRequest)event.getAsyncContext().getRequest()).getRequestURI());
            }                
            
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