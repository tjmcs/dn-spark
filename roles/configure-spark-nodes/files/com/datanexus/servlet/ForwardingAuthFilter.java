package com.datanexus.servlet;
  
import java.io.IOException;
import java.nio.charset.StandardCharsets;
  
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;

/*
 * A dummy servlet filter to pass along authentication information received in
 * an HTTP request forwarded from a reverse proxy. Assumes that the reverse
 * proxy has already authenticated the user and the information in the request
 * just needs to be forwarded along to the underlying Spark master's UI
 */
public class ForwardingAuthFilter implements Filter {
  
    @Override
    public void destroy() {
        // Nothing to do.
    }
  
    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
            final FilterChain chain ) throws IOException, ServletException {
  
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;
  
        final String auth = httpRequest.getHeader("Authorization");
        /*
         * if an authorization block was found in the request, and the base64
         * decoded version of that field contains two fields, then pass the
         * request along to the next filter in the chain
         */
        if ( auth != null ) {
            final int index = auth.indexOf(' ');
            if ( index > 0 ) {
                final String[] credentials =
                        ( new String( Base64.decodeBase64( auth.substring( index ) ), StandardCharsets.UTF_8 )).split( ":" );
                if ( credentials.length == 2) {
                    chain.doFilter( httpRequest, httpResponse );
                    return;
                }
            }
        }
        /*
         * otherwise, return an error stating that the user is not authorized
         * to access the underlying resource
         */
        httpResponse.sendError( HttpServletResponse.SC_UNAUTHORIZED );
    }
  
    @Override
    public void init( final FilterConfig config ) throws ServletException {

    }
}