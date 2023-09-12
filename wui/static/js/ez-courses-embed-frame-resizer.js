window.addEventListener("message", function (m) {
    try {
        if (JSON.parse(m.data).type !== "ez-frame-resize") {
            console.log('Ez frame resizer - got message with unknown type ' + update.type)
            return
        }

        document.querySelector("object[data=\"" + JSON.parse(m.data).url + "\"]")
            .setAttribute("height", JSON.parse(m.data).height + "px")

    } catch (error) {
        console.log('Ez frame resizer - got non-JSON message')
    }
});
