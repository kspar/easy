window.addEventListener("message", m => {
    try {
        const update = JSON.parse(m.data)
        if (update.type !== "ez-frame-resize") {
            console.log("Ez frame resizer - got message with unknown type " + update.type)
            return
        }

        document.querySelector("iframe[src=\"" + decodeURI(update.url) + "\"]")
            .setAttribute("height", update.height + "px")

    } catch (error) {
    }
});
