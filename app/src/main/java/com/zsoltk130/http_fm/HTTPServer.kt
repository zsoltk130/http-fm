package com.zsoltk130.http_fm

import fi.iki.elonen.NanoHTTPD

class HTTPServer (port: Int = 8080) : NanoHTTPD(port){
    override fun serve(session: IHTTPSession): Response {
        val html = """
            <html>
                <head><title>Hello</title></head>
                <body>
                    <h1>Hello World</h1>
                </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }
}