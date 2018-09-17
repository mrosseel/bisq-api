package network.bisq.api.service;

import java.io.IOException;

import bisq.core.btc.wallet.BtcWalletService;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class AuthFilter implements Filter {


    private final BtcWalletService btcWalletService;
    private final TokenRegistry tokenRegistry;

    public AuthFilter(BtcWalletService btcWalletService, TokenRegistry tokenRegistry) {
        this.btcWalletService = btcWalletService;
        this.tokenRegistry = tokenRegistry;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        final HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        final HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
        final String pathInfo = httpServletRequest.getPathInfo();
        final boolean isOptionsMethod = "options".equalsIgnoreCase(httpServletRequest.getMethod());
        final boolean isApiPath = pathInfo.startsWith("/api");
        final boolean isAuthEndpoint = pathInfo.endsWith("/user/authenticate");
        final boolean isPasswordEndpoint = pathInfo.endsWith("/user/password");
        if (isOptionsMethod || !isApiPath || isAuthEndpoint || isPasswordEndpoint) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        if (!btcWalletService.isWalletReady()) {
            httpServletResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (!btcWalletService.isEncrypted()) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        final String authorizationHeader = httpServletRequest.getHeader("authorization");
        if (null == authorizationHeader) {
            httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (tokenRegistry.isValidToken(authorizationHeader))
            filterChain.doFilter(servletRequest, servletResponse);
        else
            httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Override
    public void destroy() {

    }
}
