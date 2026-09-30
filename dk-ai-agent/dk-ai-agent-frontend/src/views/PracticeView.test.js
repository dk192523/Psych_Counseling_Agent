import { enableAutoUnmount, flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PracticeView from './PracticeView.vue'
import { getPracticeQuestions, submitPracticeAnswer } from '../api'

vi.mock('../api', () => ({ getPracticeQuestions: vi.fn(), submitPracticeAnswer: vi.fn() }))
enableAutoUnmount(afterEach)

const question = (id, text) => ({
  id,
  text,
  options: { A: '选项甲', B: '选项乙' },
  domain: '专业基础知识',
  source: '本地原创模拟练习，非考试真题'
})
const questions = [question('cbt-1', 'CBT 模拟题'), question('assessment-2', '心理评估模拟题')]
const answer = (correct = true) => ({
  correct,
  correctOption: 'B',
  explanation: '本题解析：结合题设条件判断。',
  sourceUrl: 'classpath:exam/practice/seed.jsonl#cbt-1'
})
const deferred = () => {
  let resolve
  const promise = new Promise(res => { resolve = res })
  return { promise, resolve }
}
const openPage = () => mount(PracticeView, { global: { stubs: { RouterLink: RouterLinkStub } } })
const button = (wrapper, label) => {
  const found = wrapper.findAll('button').find(item => item.text() === label)
  if (!found) throw new Error(`未找到按钮：${label}`)
  return found
}
const chooseAndSubmit = async (wrapper, option) => {
  await wrapper.get(`input[value="${option}"]`).setValue()
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('模拟练习页面', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    getPracticeQuestions.mockResolvedValue(questions)
    submitPracticeAnswer.mockResolvedValue(answer())
  })

  it('从加载、选择和提交到解析与下一题，并阻止等待中的重复提交', async () => {
    const loading = deferred()
    const submitting = deferred()
    getPracticeQuestions.mockReturnValueOnce(loading.promise)
    submitPracticeAnswer.mockReturnValueOnce(submitting.promise)
    const wrapper = openPage()
    await nextTick()
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载题目')
    expect(getPracticeQuestions).toHaveBeenCalledWith(5, expect.any(AbortSignal))

    loading.resolve(questions)
    await flushPromises()
    expect(wrapper.get('legend').text()).toBe('CBT 模拟题')
    expect(button(wrapper, '提交答案').element.disabled).toBe(true)
    expect(wrapper.find('.answer-result').exists()).toBe(false)

    await wrapper.get('input[value="B"]').setValue()
    await wrapper.get('form').trigger('submit')
    expect(button(wrapper, '提交中…').element.disabled).toBe(true)
    expect(button(wrapper, '重新开始').element.disabled).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(submitPracticeAnswer).toHaveBeenCalledTimes(1)
    expect(submitPracticeAnswer).toHaveBeenCalledWith('cbt-1', 'B', expect.any(AbortSignal))

    submitting.resolve(answer())
    await flushPromises()
    expect(wrapper.get('.answer-result').text()).toContain('回答正确')
    expect(wrapper.get('.answer-result').text()).toContain('正确答案：B · 选项乙')
    expect(wrapper.get('.answer-result').text()).toContain('结合题设条件判断')
    expect(wrapper.get('.answer-result').text()).toContain('classpath:exam/practice/seed.jsonl')
    expect(wrapper.find('a[href^="classpath:"]').exists()).toBe(false)
    expect(wrapper.get('fieldset').element.disabled).toBe(true)

    await button(wrapper, '下一题').trigger('click')
    expect(wrapper.get('legend').text()).toBe('心理评估模拟题')
    expect(wrapper.findAll('input').every(input => !input.element.checked)).toBe(true)
    expect(wrapper.find('.answer-result').exists()).toBe(false)
    expect(button(wrapper, '提交答案').element.disabled).toBe(true)
  })

  it('重新开始后清除上一轮成绩，新一轮按自己的答案统计', async () => {
    getPracticeQuestions.mockResolvedValue([questions[0]])
    submitPracticeAnswer.mockResolvedValueOnce(answer(true)).mockResolvedValueOnce(answer(false))
    const wrapper = openPage()
    await flushPromises()
    await chooseAndSubmit(wrapper, 'B')
    await button(wrapper, '查看本轮结果').trigger('click')
    expect(wrapper.text()).toContain('共 1 题，答对 1 题')

    await button(wrapper, '重新开始').trigger('click')
    await flushPromises()
    expect(getPracticeQuestions).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('本轮练习完成')
    expect(wrapper.findAll('input').every(input => !input.element.checked)).toBe(true)
    await chooseAndSubmit(wrapper, 'A')
    expect(wrapper.get('.answer-result').text()).toContain('回答错误')
    await button(wrapper, '查看本轮结果').trigger('click')
    expect(wrapper.text()).toContain('共 1 题，答对 0 题')
  })

  it('加载失败后允许重试，成功后消除错误并展示题目', async () => {
    getPracticeQuestions.mockRejectedValueOnce(new Error('network unavailable'))
    const wrapper = openPage()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('题目加载失败')
    expect(wrapper.find('form').exists()).toBe(false)

    await button(wrapper, '重试加载').trigger('click')
    await flushPromises()
    expect(getPracticeQuestions).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('legend').text()).toBe('CBT 模拟题')
  })

  it('提交失败保留选项供重试，重试成功后才能进入下一题', async () => {
    submitPracticeAnswer.mockRejectedValueOnce(new Error('network unavailable'))
    const wrapper = openPage()
    await flushPromises()
    await chooseAndSubmit(wrapper, 'B')
    expect(wrapper.get('[role="alert"]').text()).toContain('已保留你的选项')
    expect(wrapper.get('input[value="B"]').element.checked).toBe(true)
    expect(wrapper.find('.answer-result').exists()).toBe(false)
    expect(wrapper.findAll('button').some(item => item.text() === '下一题')).toBe(false)

    await button(wrapper, '重试提交').trigger('submit')
    await flushPromises()
    expect(submitPracticeAnswer).toHaveBeenCalledTimes(2)
    expect(submitPracticeAnswer.mock.calls.map(call => call.slice(0, 2))).toEqual([
      ['cbt-1', 'B'], ['cbt-1', 'B']
    ])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('.answer-result').text()).toContain('回答正确')
    expect(button(wrapper, '下一题').exists()).toBe(true)
  })
})
