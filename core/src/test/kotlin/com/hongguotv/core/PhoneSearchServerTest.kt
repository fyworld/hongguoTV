package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneSearchServerTest {
    private fun request(server: PhoneSearchServer,method: String="GET",path: String?=null,body: String="",headers: String="",host: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n"+headers+if(method=="POST") "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\n\r\n" else "\r\n").toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `phone form accepts repeated unicode searches within the session without reflecting them in HTML`() {
        val received=CountDownLatch(2); val queries=java.util.Collections.synchronizedList(mutableListOf<String>())
        PhoneSearchServer("127.0.0.1",{ queries.add(it); received.countDown() }).use { server ->
            val page=request(server)
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store")); assertTrue(page.contains("form-action 'self'"))
            val value="测试 <script>alert(1)</script>"
            val reply=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode(value,"UTF-8"))
            assertTrue(reply.startsWith("HTTP/1.1 200")); assertFalse(reply.contains(value))
            // Session stays alive: a second query must go through too (previously the
            // first submit closed the server and every retry needed a new QR code).
            val second=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode("第二季","UTF-8"))
            assertTrue(second.startsWith("HTTP/1.1 200"))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals(listOf(value,"第二季"),queries)
            assertTrue(request(server).startsWith("HTTP/1.1 200"))  // page still served
        }
    }
    @Test fun `wrong token host and cross site posts cannot submit`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            assertTrue(request(server,path="/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=test","Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    @Test fun `origin null and missing origin are accepted like real scanner webviews`() {
        // WeChat X5 / camera-app webviews send "Origin: null"; plain form posts send none.
        // Both must go through, or every real phone got 来源不匹配 (v1.0.10 regression).
        for(origin in listOf("", "Origin: null\r\n")) {
            val received=CountDownLatch(1); var query=""
            PhoneSearchServer("127.0.0.1",{ query=it; received.countDown() }).use { server ->
                val reply=request(server,"POST",URI(server.url).path+"/submit","query=test",origin)
                assertTrue(reply.startsWith("HTTP/1.1 200"))
                assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals("test",query)
            }
        }
    }
    @Test fun `invalid and oversized input does not consume the session`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            for(body in listOf("query=","query="+"x".repeat(81),"query=a&query=b","query=hello%0Aworld","query=%zz")) {
                val response=request(server,"POST",URI(server.url).path+"/submit",body)
                assertTrue(response,response.startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query="+"x".repeat(4096)).startsWith("HTTP/1.1 413"))
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `closing or expiry makes a session unreachable`() {
        val closed=PhoneSearchServer("127.0.0.1",{}); closed.close()
        assertTrue(runCatching { request(closed) }.isFailure)
        val expired=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ fail("expired session submitted") },{ expired.countDown() },1).use { server ->
            assertTrue(expired.await(2,TimeUnit.SECONDS))
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
}
