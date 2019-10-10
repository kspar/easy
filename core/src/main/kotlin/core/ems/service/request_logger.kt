package core.ems.service

//import mu.KotlinLogging
//import org.springframework.context.annotation.Configuration
//import org.springframework.lang.Nullable
//import org.springframework.stereotype.Component
//import org.springframework.web.filter.GenericFilterBean
//import org.springframework.web.servlet.config.annotation.InterceptorRegistry
//import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
//import org.springframework.web.servlet.handler.HandlerInterceptorAdapter
//import org.springframework.web.util.ContentCachingRequestWrapper
//import org.springframework.web.util.ContentCachingResponseWrapper
//import org.springframework.web.util.WebUtils
//import java.io.IOException
//import javax.servlet.FilterChain
//import javax.servlet.ServletException
//import javax.servlet.ServletRequest
//import javax.servlet.ServletResponse
//import javax.servlet.http.HttpServletRequest
//import javax.servlet.http.HttpServletResponse
//
//private val log = KotlinLogging.logger {}
//
//@Configuration
//class LoggerConf : WebMvcConfigurer {
//    override fun addInterceptors(registry: InterceptorRegistry) {
//        registry.addInterceptor(LoggerInterceptor())
//    }
//}
//
//class LoggerInterceptor : HandlerInterceptorAdapter() {
//
//    @Throws(Exception::class)
//    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
//        request.setAttribute("startTime", System.currentTimeMillis())
//        return true
//    }
//
//    @Throws(Exception::class)
//    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, @Nullable ex: java.lang.Exception?) {
//        super.afterCompletion(request, response, handler, ex)
//        request.setAttribute("endTime", System.currentTimeMillis())
//    }
//}
//
//@Component
//class CachingRequestBodyFilter : GenericFilterBean() {
//    // https://stackoverflow.com/questions/6631257/how-to-log-properly-http-requests-with-spring-mvc
//
//    @Throws(IOException::class, ServletException::class)
//    override fun doFilter(req: ServletRequest, resp: ServletResponse, chain: FilterChain) {
//        val currentRequest = req as HttpServletRequest
//        val currentResponse = resp as HttpServletResponse
//
//        // For caching
//        val wrappedRequest = ContentCachingRequestWrapper(currentRequest)
//        val wrappedResponse = ContentCachingResponseWrapper(currentResponse)
//
//        // Currently readable parameters
//        val path = "${req.requestURI}?${req.queryString ?: ""}"
//        val method = req.method
//        val ip = req.getHeader("X-FORWARDED-FOR") ?: req.remoteAddr
//        val user = req.getHeader("oidc_claim_preferred_username")
//        val role = req.getHeader("oidc_claim_easy_role")
//
//        // Pass on wrapped request and response: these will cache request and response as soon as they are used in chain
//        chain.doFilter(wrappedRequest, wrappedResponse)
//        val statusCode = wrappedResponse.statusCode
//
//        // Parameters that were not readable before:
//        val requestBody: String = getRequestBody(wrappedRequest).take(500).replace(Regex("[\n\r]"), "")
//        val responseBody: String = getResponseBody(wrappedResponse).take(500).replace(Regex("[\n\r]"), "")
//
//        // Set attributes to 0 if request method not supported
//        val startTime = (req.getAttribute("startTime")?:0.toLong()) as Long
//        val endTime = (req.getAttribute("endTime")?:0.toLong()) as Long
//        val executeTime = endTime - startTime
//
//        // Add extra 'close' as they might be opened by some other method internally and left unclosed
//        wrappedRequest.inputStream.close()
//        wrappedResponse.contentInputStream.close()
//
//        log.trace { "${executeTime}ms::$user::$role::$ip::$method::$statusCode::${path}::[${requestBody}]->[${responseBody}]" }
//    }
//
//    private fun getResponseBody(wrappedResponse: ContentCachingResponseWrapper): String {
//        val wrapper = WebUtils.getNativeResponse(wrappedResponse, ContentCachingResponseWrapper::class.java)
//        val responseBody = wrapper?.contentAsByteArray?.toString(charset("UTF-8")) ?: ""
//        wrapper?.copyBodyToResponse()
//        return responseBody
//    }
//
//    private fun getRequestBody(wrappedRequest: ContentCachingRequestWrapper): String {
//        val wrapper = WebUtils.getNativeRequest(wrappedRequest, ContentCachingRequestWrapper::class.java)
//        return wrapper?.contentAsByteArray?.toString(charset("UTF-8")) ?: ""
//    }
//}
