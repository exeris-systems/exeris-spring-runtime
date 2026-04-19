/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import eu.exeris.spring.runtime.web.compat.context.ExerisThreadLocalBridge;

import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * Compatibility-mode dispatch bridge between the Exeris request model and the Spring
 * handler invocation model.
 *
 * <p>Exeris remains the runtime owner. This class translates the kernel
 * {@link HttpExchange} into the Spring programming-model surface, invokes
 * the matched handler, and returns the response back to the Exeris caller.
 *
 * <p>ThreadLocal binding is performed only in {@link ExerisThreadLocalBridge},
 * deterministically cleared in {@code finally}, isolated from the pure-mode path.
 */
public final class ExerisSpringMvcBridge {

    public sealed interface DispatchResult permits DispatchResult.Handled, DispatchResult.NotHandled {
        record Handled(ExerisServerResponse response) implements DispatchResult {}
        record NotHandled() implements DispatchResult {}
        DispatchResult NOT_HANDLED = new NotHandled();
    }

    private final ExerisHandlerMethodRegistry registry;
    private final HandlerMethodArgumentResolverComposite argumentResolvers;
    private final HandlerMethodReturnValueHandlerComposite returnValueHandlers;
    private final ExerisExceptionHandlerResolver exceptionHandlerResolver;
    private final ExerisThreadLocalBridge threadLocalBridge;

    public ExerisSpringMvcBridge(ExerisHandlerMethodRegistry registry,
                                  HandlerMethodArgumentResolverComposite argumentResolvers,
                                  HandlerMethodReturnValueHandlerComposite returnValueHandlers,
                                  ExerisExceptionHandlerResolver exceptionHandlerResolver,
                                  ExerisThreadLocalBridge threadLocalBridge) {
        this.registry = registry;
        this.argumentResolvers = argumentResolvers;
        this.returnValueHandlers = returnValueHandlers;
        this.exceptionHandlerResolver = exceptionHandlerResolver;
        this.threadLocalBridge = threadLocalBridge;
    }

    /**
     * Dispatches an {@link HttpExchange} through the Spring handler invocation model.
     *
     * @return {@link DispatchResult.Handled} with the response, or
     *         {@link DispatchResult.NotHandled} if no route matched.
     */
    public DispatchResult dispatch(HttpExchange exchange) throws Exception {
        var kernelRequest = exchange.request();
        ExerisServerRequest serverRequest = ExerisServerRequest.wrap(kernelRequest);
        ExerisMvcServerHttpRequest springRequest = new ExerisMvcServerHttpRequest(serverRequest);
        ExerisMvcServerHttpResponse springResponse = new ExerisMvcServerHttpResponse();
        ExerisNativeWebRequest nativeRequest = new ExerisNativeWebRequest(springRequest);

        Optional<ExerisHandlerMethodRegistry.ResolvedHandler> resolved =
                registry.resolve(serverRequest.method(), serverRequest.path());

        if (resolved.isEmpty()) {
            return DispatchResult.NOT_HANDLED;
        }

        ExerisHandlerMethodRegistry.ResolvedHandler resolvedHandler = resolved.get();
        nativeRequest.setAttribute(ExerisHandlerMethodRegistry.PATH_VARIABLES_ATTRIBUTE,
                resolvedHandler.pathVariables(), RequestAttributes.SCOPE_REQUEST);
        nativeRequest.setAttribute("__exerisSpringResponse", springResponse, RequestAttributes.SCOPE_REQUEST);

        threadLocalBridge.bind(springRequest);
        try {
            return invokeHandler(resolvedHandler.handlerMethod(), nativeRequest, springResponse);
        } catch (Exception ex) {
            exceptionHandlerResolver.resolve(ex, resolvedHandler.handlerMethod(), nativeRequest, springResponse);
            return new DispatchResult.Handled(springResponse.toExerisServerResponse());
        } finally {
            threadLocalBridge.clear();
        }
    }

    private DispatchResult.Handled invokeHandler(HandlerMethod handlerMethod,
                                                  NativeWebRequest nativeRequest,
                                                  ExerisMvcServerHttpResponse springResponse) throws Exception {
        InvocableHandlerMethod ihm = new InvocableHandlerMethod(handlerMethod);
        ihm.setHandlerMethodArgumentResolvers(argumentResolvers);

        ModelAndViewContainer mavContainer = new ModelAndViewContainer();

        Object returnValue = ihm.invokeForRequest(nativeRequest, mavContainer);

        if (!mavContainer.isRequestHandled()) {
            returnValueHandlers.handleReturnValue(
                    returnValue,
                    handlerMethod.getReturnValueType(returnValue),
                    mavContainer,
                    nativeRequest);
        }

        return new DispatchResult.Handled(springResponse.toExerisServerResponse());
    }
}

