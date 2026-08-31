FROM python:3.12-slim

ENV PYTHONUTF8=1 \
    PYTHONIOENCODING=utf-8 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir .

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000
# --factory：应用在 uvicorn 调用 create_app() 时才构造，鉴权配置的 fail-closed 校验因此发生在
# 服务启动时，而不是模块导入时。模块级 `app = create_app()` 会让"导入 main"这个动作本身带上
# 环境依赖——测试与任何工具链导入该模块都会直接抛错。
CMD ["uvicorn", "--factory", "dk_ai_worker.main:create_app", "--host", "0.0.0.0", "--port", "8000"]
