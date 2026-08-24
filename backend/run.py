import uvicorn
import logging

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,   # enable reload in development
        log_level="info",
        ws_ping_interval=20,
        ws_ping_timeout=30,
    )
